/*
 * Copyright 2003-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.transform.stc;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.*;
import org.codehaus.groovy.ast.tools.GenericsUtils;
import org.codehaus.groovy.classgen.ReturnAdder;
import org.codehaus.groovy.classgen.asm.InvocationWriter;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.runtime.MetaClassHelper;
import org.codehaus.groovy.runtime.typehandling.FloatingPointMath;
import org.codehaus.groovy.transform.StaticTypesTransformation;
import org.codehaus.groovy.util.ListHashMap;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.codehaus.groovy.ast.ClassHelper.*;
import static org.codehaus.groovy.ast.tools.WideningCategories.*;
import static org.codehaus.groovy.syntax.Types.*;
import static org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport.*;

/**
 * The main class code visitor responsible for static type checking. It will perform various inspections like checking
 * assignment types, type inference, ... Eventually, class nodes may be annotated with inferred type information.
 *
 * @author Cedric Champeau
 * @author Jochen Theodorou
 */
public class StaticTypeCheckingVisitor extends ClassCodeVisitorSupport {
    private static final ClassNode ITERABLE_TYPE = ClassHelper.make(Iterable.class);
    private final static ClassNode READONLY_PROPERTY_RETURN = ClassHelper.make("<readonly>");
    private final static List<MethodNode> EMPTY_METHODNODE_LIST = Collections.emptyList();

    private SourceUnit source;
    private ClassNode classNode;
    private MethodNode methodNode;
    private Set<MethodNode> methodsToBeVisited = Collections.emptySet();

    // used for closure return type inference
    private ClosureExpression closureExpression;
    private List<ClassNode> closureReturnTypes;

    // whenever a "with" method call is detected, this list is updated
    // with the receiver type of the with method
    private LinkedList<ClassNode> withReceiverList = new LinkedList<ClassNode>();
    /**
     * The type of the last encountered "it" implicit parameter
     */
    private ClassNode lastImplicitItType;

    /**
     * This field is used to track assignments in if/else branches, for loops and while loops. For example, in the following code:
     * if (cond) { x = 1 } else { x = '123' }
     * the inferred type of x after the if/else statement should be the LUB of (int, String)
     */
    private Map<VariableExpression, List<ClassNode>> ifElseForWhileAssignmentTracker = null;

    /**
     * Stores information which is only valid in the "if" branch of an if-then-else statement. This is used when the if
     * condition expression makes use of an instanceof check
     */
    private Stack<Map<Object, List<ClassNode>>> temporaryIfBranchTypeInformation;

    private Set<MethodNode> alreadyVisitedMethods = new HashSet<MethodNode>();

	/**
	 * Some expressions need to be visited twice, because type information may be insufficient at some
	 * point. For example, for closure shared variables, we need a first pass to collect every type which
	 * is assigned to a closure shared variable, then a second pass to ensure that every method call on
	 * such a variable is made on a LUB.
	 */
	private final LinkedHashSet<Expression> secondPassExpressions = new LinkedHashSet<Expression>();

	/**
	 * A map used to store every type used in closure shared variable assignments. In a second pass, we will
	 * compute the LUB of each type and check that method calls on those variables are valid.
	 */
	private final Map<VariableExpression, List<ClassNode>> closureSharedVariablesAssignmentTypes = new HashMap<VariableExpression, List<ClassNode>>();

    /**
     * The plugin factory used to extend the type checker capabilities.
     */
    private final TypeCheckerPluginFactory pluginFactory;

    private Map<Parameter, ClassNode> forLoopVariableTypes = new HashMap<Parameter, ClassNode>();
    
    private final ReturnAdder returnAdder = new ReturnAdder(new ReturnAdder.ReturnStatementListener() {
        public void returnStatementAdded(final ReturnStatement returnStatement) {
            if (returnStatement.getExpression().equals(ConstantExpression.NULL)) return;
            ClassNode returnType = checkReturnType(returnStatement);
            if (methodNode!=null) {
                ClassNode previousType = (ClassNode) methodNode.getNodeMetaData(StaticTypesMarker.INFERRED_RETURN_TYPE);
                ClassNode inferred = previousType==null?returnType: lowestUpperBound(returnType, previousType);
                methodNode.putNodeMetaData(StaticTypesMarker.INFERRED_RETURN_TYPE, inferred);
            }
        }
    });

    private final ReturnAdder closureReturnAdder = new ReturnAdder(new ReturnAdder.ReturnStatementListener() {
        public void returnStatementAdded(final ReturnStatement returnStatement) {
            if (returnStatement.getExpression().equals(ConstantExpression.NULL)) return;
            MethodNode currentNode = methodNode;
            methodNode = null;
            try {
                checkReturnType(returnStatement);
                if (closureExpression!=null) {
                    addClosureReturnType(getType(returnStatement.getExpression()));
                }
            } finally {
                methodNode = currentNode;
            }
        }
    });

    public StaticTypeCheckingVisitor(SourceUnit source, ClassNode cn, TypeCheckerPluginFactory pluginFactory) {
        this.source = source;
        this.classNode = cn;
        this.temporaryIfBranchTypeInformation = new Stack<Map<Object, List<ClassNode>>>();
        this.pluginFactory = pluginFactory;
        pushTemporaryTypeInfo();
    }

    //        @Override
    protected SourceUnit getSourceUnit() {
        return source;
    }

    @Override
    public void visitClass(final ClassNode node) {
        ClassNode oldCN = classNode;
        classNode = node;
        super.visitClass(node);
        classNode = oldCN;
    }


    @Override
    public void visitVariableExpression(VariableExpression vexp) {
        super.visitVariableExpression(vexp);
        if (vexp != VariableExpression.THIS_EXPRESSION &&
                vexp != VariableExpression.SUPER_EXPRESSION) {
            if (vexp.getName().equals("this")) storeType(vexp, classNode);
            if (vexp.getName().equals("super")) storeType(vexp, classNode.getSuperClass());
        }
        if (vexp.getAccessedVariable() instanceof DynamicVariable) {
            // a dynamic variable is either an undeclared variable
            // or a member of a class used in a 'with'
            DynamicVariable dyn = (DynamicVariable) vexp.getAccessedVariable();
            // first, we must check the 'with' context
            String dynName = dyn.getName();
            for (ClassNode node : withReceiverList) {
                if (node.getProperty(dynName) != null) {
                    storeType(vexp, node.getProperty(dynName).getType());
                    return;
                }
                if (node.getField(dynName) != null) {
                    storeType(vexp, node.getField(dynName).getType());
                    return;
                }
            }
            
            // lookup with plugin
            if (pluginFactory!=null) {
                TypeCheckerPlugin plugin = pluginFactory.getTypeCheckerPlugin(classNode);
                if (plugin!=null) {
                    ClassNode type = plugin.resolveDynamicVariableType(dyn);
                    if (type != null) {
                        storeType(vexp, type);
                        return;
                    }
                }
            }
            
            addStaticTypeError("The variable [" + vexp.getName() + "] is undeclared.", vexp);
        }
    }

    @Override
    public void visitPropertyExpression(final PropertyExpression pexp) {
        super.visitPropertyExpression(pexp);
        if (!existsProperty(pexp, true)) {
            Expression objectExpression = pexp.getObjectExpression();
            addStaticTypeError("No such property: " + pexp.getPropertyAsString() +
                    " for class: " + findCurrentInstanceOfClass(objectExpression, objectExpression.getType()), pexp);
        }
    }

    @Override
    public void visitAttributeExpression(final AttributeExpression expression) {
        super.visitAttributeExpression(expression);
        if (!existsProperty(expression, true)) {
            Expression objectExpression = expression.getObjectExpression();
            addStaticTypeError("No such property: " + expression.getPropertyAsString() +
                    " for class: " + findCurrentInstanceOfClass(objectExpression, objectExpression.getType()), expression);
        }
    }

    @Override
    public void visitBinaryExpression(BinaryExpression expression) {
        super.visitBinaryExpression(expression);
        final Expression leftExpression = expression.getLeftExpression();
        ClassNode lType = getType(leftExpression);
        final Expression rightExpression = expression.getRightExpression();
        ClassNode rType = getType(rightExpression);
        if (rightExpression instanceof ConstantExpression && ((ConstantExpression) rightExpression).getValue()==null) {
            if (!isPrimitiveType(lType)) rType = lType; // primitive types should be ignored as they will result in another failure
        }
        int op = expression.getOperation().getType();
        ClassNode resultType = getResultType(lType, op, rType, expression);
        if (resultType == null) {
            resultType = lType;
        }
        boolean isEmptyDeclaration = expression instanceof DeclarationExpression && rightExpression instanceof EmptyExpression;
        if (!isEmptyDeclaration) storeType(expression, resultType);
        if (!isEmptyDeclaration && isAssignment(op)) {
            if (rightExpression instanceof ConstructorCallExpression) {
                inferDiamondType((ConstructorCallExpression) rightExpression, lType);
            }

            typeCheckAssignment(expression, leftExpression, lType, rightExpression, rType);

            // if we are in an if/else branch, keep track of assignment
            if (ifElseForWhileAssignmentTracker !=null && leftExpression instanceof VariableExpression) {
                Variable accessedVariable = ((VariableExpression) leftExpression).getAccessedVariable();
                if (accessedVariable instanceof VariableExpression) {
                    VariableExpression var = (VariableExpression) accessedVariable;
                    List<ClassNode> types = ifElseForWhileAssignmentTracker.get(var);
                    if (types == null) {
                        types = new LinkedList<ClassNode>();
                        ClassNode type = (ClassNode) var.getNodeMetaData(StaticTypesMarker.INFERRED_TYPE);
                        if (type!=null) types.add(type);
                        ifElseForWhileAssignmentTracker.put(var, types);
                    }
                    types.add(resultType);
                }
            }
            storeType(leftExpression, resultType);

            // if right expression is a ClosureExpression, store parameter type information
            if (leftExpression instanceof VariableExpression && rightExpression instanceof ClosureExpression) {
                Parameter[] parameters = ((ClosureExpression) rightExpression).getParameters();
                leftExpression.putNodeMetaData(StaticTypesMarker.CLOSURE_ARGUMENTS, parameters);
            }


        } else if (op == KEYWORD_INSTANCEOF) {
            pushInstanceOfTypeInfo(leftExpression, rightExpression);
        }
    }

    private void inferDiamondType(final ConstructorCallExpression cce, final ClassNode lType) {
        // check if constructor call expression makes use of the diamond operator
        ClassNode node = cce.getType();
        if (node.isUsingGenerics() && node.getGenericsTypes().length==0) {
            ArgumentListExpression argumentListExpression = InvocationWriter.makeArgumentList(cce.getArguments());
            if (argumentListExpression.getExpressions().isEmpty()) {
                GenericsType[] genericsTypes = lType.getGenericsTypes();
                GenericsType[] copy = new GenericsType[genericsTypes.length];
                for (int i = 0; i < genericsTypes.length; i++) {
                    GenericsType genericsType = genericsTypes[i];
                    copy[i] = new GenericsType(
                            wrapTypeIfNecessary(genericsType.getType()),
                            genericsType.getUpperBounds(),
                            genericsType.getLowerBound()
                    );
                }
                node.setGenericsTypes(copy);
            } else {
                ClassNode type = getType(argumentListExpression.getExpression(0));
                if (type.isUsingGenerics()) {
                    GenericsType[] genericsTypes = type.getGenericsTypes();
                    GenericsType[] copy = new GenericsType[genericsTypes.length];
                    for (int i = 0; i < genericsTypes.length; i++) {
                        GenericsType genericsType = genericsTypes[i];
                        copy[i] = new GenericsType(
                                wrapTypeIfNecessary(genericsType.getType()),
                                genericsType.getUpperBounds(),
                                genericsType.getLowerBound()
                        );
                    }
                    node.setGenericsTypes(copy);
                }
            }
        }
    }

    /**
     * Stores information about types when [objectOfInstanceof instanceof typeExpression] is visited
     * @param objectOfInstanceOf the expression which must be checked against instanceof
     * @param typeExpression the expression which represents the target type
     */
    private void pushInstanceOfTypeInfo(final Expression objectOfInstanceOf, final Expression typeExpression) {
        final Map<Object, List<ClassNode>> tempo = temporaryIfBranchTypeInformation.peek();
        Object key = extractTemporaryTypeInfoKey(objectOfInstanceOf);
        List<ClassNode> potentialTypes = tempo.get(key);
        if (potentialTypes == null) {
            potentialTypes = new LinkedList<ClassNode>();
            tempo.put(key, potentialTypes);
        }
        potentialTypes.add(typeExpression.getType());
    }

    private void typeCheckAssignment(
            final BinaryExpression assignmentExpression,
            final Expression leftExpression,
            final ClassNode leftExpressionType,
            final Expression rightExpression,
            final ClassNode inferredRightExpressionType) {
        ClassNode leftRedirect;
        if (isArrayAccessExpression(leftExpression) || leftExpression instanceof PropertyExpression
                || (leftExpression instanceof VariableExpression
                && ((VariableExpression) leftExpression).getAccessedVariable() instanceof DynamicVariable)) {
            // in case the left expression is in the form of an array access, we should use
            // the inferred type instead of the left expression type.
            // In case we have a variable expression which accessed variable is a dynamic variable, we are
            // in the "with" case where the type must be taken from the inferred type
            leftRedirect = leftExpressionType;
        } else {
            if (leftExpression instanceof VariableExpression && isPrimitiveType(((VariableExpression)leftExpression).getOriginType())) {
                leftRedirect = leftExpressionType;
            } else {
                leftRedirect = leftExpression.getType().redirect();
            }
        }
        if (leftExpression instanceof TupleExpression) {
            // multiple assignment
            if (!(rightExpression instanceof ListExpression)) {
                addStaticTypeError("Multiple assignments without list expressions on the right hand side are unsupported in static type checking mode", rightExpression);
                return;
            }
            TupleExpression tuple = (TupleExpression) leftExpression;
            ListExpression list = (ListExpression) rightExpression;
            List<Expression> listExpressions = list.getExpressions();
            List<Expression> tupleExpressions = tuple.getExpressions();
            if (listExpressions.size()< tupleExpressions.size()) {
                addStaticTypeError("Incorrect number of values. Expected:"+ tupleExpressions.size()+" Was:"+listExpressions.size(), list);
                return;
            }
            for (int i = 0, tupleExpressionsSize = tupleExpressions.size(); i < tupleExpressionsSize; i++) {
                Expression tupleExpression = tupleExpressions.get(i);
                Expression listExpression = listExpressions.get(i);
                ClassNode elemType = getType(listExpression);
                ClassNode tupleType = getType(tupleExpression);
                if (!isAssignableTo(elemType, tupleType)) {
                    addStaticTypeError("Cannot assign value of type " + elemType.getName() + " to variable of type " + tupleType.getName(), rightExpression);
                    break; // avoids too many errors
                }
            }
            return;
        }
        boolean compatible = checkCompatibleAssignmentTypes(leftRedirect, inferredRightExpressionType, rightExpression);
        if (!compatible) {
            // if leftRedirect is of READONLY_PROPERTY_RETURN type, then it means we are on a missing property
            if ((leftRedirect == READONLY_PROPERTY_RETURN) && (leftExpression instanceof PropertyExpression)) {
                addStaticTypeError("Cannot set read-only property: "+((PropertyExpression)leftExpression).getPropertyAsString(), leftExpression);
            } else {
                addStaticTypeError("Cannot assign value of type " + inferredRightExpressionType.getName() + " to variable of type " + leftExpressionType.getName(), assignmentExpression);
            }
        } else {
            // if closure expression on RHS, then copy the inferred closure return type
            if (rightExpression instanceof ClosureExpression) {
                Object type = rightExpression.getNodeMetaData(StaticTypesMarker.INFERRED_RETURN_TYPE);
                if (type!=null) {
                    leftExpression.putNodeMetaData(StaticTypesMarker.INFERRED_RETURN_TYPE,type);
                }
            }

            boolean possibleLooseOfPrecision = false;
            if (isNumberType(leftRedirect) && isNumberType(inferredRightExpressionType)) {
                possibleLooseOfPrecision = checkPossibleLooseOfPrecision(leftRedirect, inferredRightExpressionType, rightExpression);
                if (possibleLooseOfPrecision) {
                    addStaticTypeError("Possible loose of precision from " + inferredRightExpressionType + " to " + leftRedirect, rightExpression);
                }
            }
            // if left type is array, we should check the right component types
            if (!possibleLooseOfPrecision && leftExpressionType.isArray()) {
                ClassNode leftComponentType = leftExpressionType.getComponentType();
                ClassNode rightRedirect = rightExpression.getType().redirect();
                if (rightRedirect.isArray()) {
                    ClassNode rightComponentType = rightRedirect.getComponentType();
                    if (!checkCompatibleAssignmentTypes(leftComponentType, rightComponentType)) {
                        addStaticTypeError("Cannot assign value of type " + rightComponentType + " into array of type " + leftExpressionType, assignmentExpression);
                    }
                } else if (rightExpression instanceof ListExpression) {
                    for (Expression element : ((ListExpression) rightExpression).getExpressions()) {
                        ClassNode rightComponentType = element.getType().redirect();
                        if (!checkCompatibleAssignmentTypes(leftComponentType, rightComponentType)) {
                            addStaticTypeError("Cannot assign value of type " + rightComponentType + " into array of type " + leftExpressionType, assignmentExpression);
                        }
                    }
                }
            }

            // if left type is not a list but right type is a list, then we're in the case of a groovy
            // constructor type : Dimension d = [100,200]
            // In that case, more checks can be performed
            if (!implementsInterfaceOrIsSubclassOf(leftRedirect,LIST_TYPE) && rightExpression instanceof ListExpression) {
                ArgumentListExpression argList = new ArgumentListExpression(((ListExpression) rightExpression).getExpressions());
                ClassNode[] args = getArgumentTypes(argList);
                checkGroovyStyleConstructor(leftRedirect, args);
            } else if (!implementsInterfaceOrIsSubclassOf(inferredRightExpressionType, leftRedirect)
                    && implementsInterfaceOrIsSubclassOf(inferredRightExpressionType, LIST_TYPE)) {
                addStaticTypeError("Cannot assign value of type " + inferredRightExpressionType.getName() + " to variable of type " + leftExpressionType.getName(), assignmentExpression);
            }

            // if left type is not a list but right type is a map, then we're in the case of a groovy
            // constructor type : A a = [x:2, y:3]
            // In this case, more checks can be performed
            if (!implementsInterfaceOrIsSubclassOf(leftRedirect,MAP_TYPE) && rightExpression instanceof MapExpression) {
                if (!(leftExpression instanceof VariableExpression) || !((VariableExpression) leftExpression).isDynamicTyped()) {
                    ArgumentListExpression argList = new ArgumentListExpression(rightExpression);
                    ClassNode[] args = getArgumentTypes(argList);
                    checkGroovyStyleConstructor(leftRedirect, args);
                    // perform additional type checking on arguments
                    MapExpression mapExpression = (MapExpression) rightExpression;
                    for (MapEntryExpression entryExpression : mapExpression.getMapEntryExpressions()) {
                        Expression keyExpr = entryExpression.getKeyExpression();
                        if (!(keyExpr instanceof ConstantExpression)) {
                            addStaticTypeError("Dynamic keys in map-style constructors are unsupported in static type checking", keyExpr);
                        } else {
                            String property = keyExpr.getText();
                            ClassNode currentNode = leftRedirect;
                            PropertyNode propertyNode = null;
                            while (propertyNode == null && currentNode != null) {
                                propertyNode = currentNode.getProperty(property);
                                currentNode = currentNode.getSuperClass();
                            }
                            if (propertyNode == null) {
                                addStaticTypeError("No such property: " + property +
                                        " for class: " + leftRedirect.getName(), leftExpression);
                            } else if (propertyNode != null) {
                                ClassNode valueType = getType(entryExpression.getValueExpression());
                                if (!isAssignableTo(propertyNode.getType(), valueType)) {
                                    addStaticTypeError("Cannot assign value of type " + valueType.getName() + " to field of type " + propertyNode.getType().getName(), entryExpression);
                                }
                            }
                        }
                    }
                }
            }

            // last, check generic type information to ensure that inferred types are compatible
            if (leftExpressionType.isUsingGenerics() && !leftExpressionType.isEnum()) {
                GenericsType gt = GenericsUtils.buildWildcardType(leftExpressionType);
                if (!gt.isCompatibleWith(inferredRightExpressionType)) {
                    addStaticTypeError("Incompatible generic argument types. Cannot assign "
                    + inferredRightExpressionType.toString(false)
                    + " to: "+leftExpressionType.toString(false), assignmentExpression);
                }
            }
        }
    }

    /**
     * Checks that a constructor style expression is valid regarding the number of arguments and the argument types.
     * @param node the class node for which we will try to find a matching constructor
     * @param arguments the constructor arguments
     */
    private void checkGroovyStyleConstructor(final ClassNode node, final ClassNode[] arguments) {
        if (node.equals(ClassHelper.OBJECT_TYPE) || node.equals(ClassHelper.DYNAMIC_TYPE)) {
            // in that case, we are facing a list constructor assigned to a def or object
            return;
        }
        List<ConstructorNode> constructors = node.getDeclaredConstructors();
        if (constructors.isEmpty() && arguments.length==0) return;
        List<MethodNode> constructorList = findMethod(node, "<init>", arguments);
        if (constructorList.isEmpty()) {
            addStaticTypeError("No matching constructor found: "+node+toMethodParametersString("<init>", arguments), classNode);
        }
    }

    /**
     * When instanceof checks are found in the code, we store temporary type information data in the {@link
     * #temporaryIfBranchTypeInformation} table. This method computes the key which must be used to store this type
     * info.
     *
     * @param expression the expression for which to compute the key
     * @return a key to be used for {@link #temporaryIfBranchTypeInformation}
     */
    private Object extractTemporaryTypeInfoKey(final Expression expression) {
        return expression instanceof VariableExpression ? findTargetVariable((VariableExpression) expression) : expression.getText();
    }

    /**
     * A helper method which determines which receiver class should be used in error messages when a field or attribute
     * is not found. The returned type class depends on whether we have temporary type information availble (due to
     * instanceof checks) and whether there is a single candidate in that case.
     *
     * @param expr the expression for which an unknown field has been found
     * @param type the type of the expression (used as fallback type)
     * @return if temporary information is available and there's only one type, returns the temporary type class
     *         otherwise falls back to the provided type class.
     */
    private ClassNode findCurrentInstanceOfClass(final Expression expr, final ClassNode type) {
        if (!temporaryIfBranchTypeInformation.empty()) {
            Object key = extractTemporaryTypeInfoKey(expr);
            List<ClassNode> nodes = temporaryIfBranchTypeInformation.peek().get(key);
            if (nodes != null && nodes.size() == 1) return nodes.get(0);
        }
        return type;
    }

    private boolean existsProperty(final PropertyExpression pexp, final boolean checkForReadOnly) {
        return existsProperty(pexp, checkForReadOnly, null);
    }

    /**
     * Checks whether a property exists on the receiver, or on any of the possible receiver classes (found in the
     * temporary type information table)
     *
     * @param pexp a property expression
     * @param checkForReadOnly also lookup for read only properties
     * @param visitor if not null, when the property node is found, visit it with the provided visitor
     * @return true if the property is defined in any of the possible receiver classes
     */
    private boolean existsProperty(final PropertyExpression pexp, final boolean checkForReadOnly, final ClassCodeVisitorSupport visitor) {
        Expression objectExpression = pexp.getObjectExpression();
        ClassNode clazz = getType(objectExpression);
        if (clazz.isArray() && "length".equals(pexp.getPropertyAsString())) {
            if (visitor!=null) {
                PropertyNode node = new PropertyNode("length", Opcodes.ACC_PUBLIC| Opcodes.ACC_FINAL, int_TYPE, clazz, null, null, null);
                storeType(pexp, int_TYPE);
                visitor.visitProperty(node);
            }
            return true;
        }
        List<ClassNode> tests = new LinkedList<ClassNode>();
        tests.add(clazz);
        if (objectExpression instanceof ClassExpression) tests.add(CLASS_Type);
        if (!temporaryIfBranchTypeInformation.empty()) {
            Map<Object, List<ClassNode>> info = temporaryIfBranchTypeInformation.peek();
            Object key = extractTemporaryTypeInfoKey(objectExpression);
            List<ClassNode> classNodes = info.get(key);
            if (classNodes != null) tests.addAll(classNodes);
        }
        if (lastImplicitItType != null
                && pexp.getObjectExpression() instanceof VariableExpression
                && ((VariableExpression) pexp.getObjectExpression()).getName().equals("it")) {
            tests.add(lastImplicitItType);
        }
        String propertyName = pexp.getPropertyAsString();
        if (propertyName==null) return false;
        boolean isAttributeExpression = pexp instanceof AttributeExpression;
        for (ClassNode testClass : tests) {
            // maps and lists have special handling for property expressions
            if (!implementsInterfaceOrIsSubclassOf(testClass,  MAP_TYPE) && !implementsInterfaceOrIsSubclassOf(testClass, LIST_TYPE)) {
                ClassNode current = testClass;
                while (current!=null) {
                    current = current.redirect();
                    PropertyNode propertyNode = current.getProperty(propertyName);
                    if (propertyNode != null) {
                        if (visitor!=null) visitor.visitProperty(propertyNode);
                        storeType(pexp, propertyNode.getOriginType());
                        return true;
                    }
                    if (!isAttributeExpression) {
                        FieldNode field = current.getDeclaredField(propertyName);
                        if (field != null) {
                            if (visitor!=null) visitor.visitField(field);
                            storeType(pexp, field.getOriginType());
                            return true;
                        }
                    }
                    // if the property expression is an attribute expression (o.@attr), then
                    // we stop now, otherwise we must check the parent class
                    current = isAttributeExpression ?null:current.getSuperClass();
                }
                if (checkForReadOnly) {
                    current = testClass;
                    while (current != null) {
                        current = current.redirect();

                        String pname = MetaClassHelper.capitalize(propertyName);
                        List<MethodNode> nodes = current.getMethods("get" + pname);
                        if (nodes.isEmpty()) nodes = current.getMethods("is" + pname);
                        if (!nodes.isEmpty()) {
                            for (MethodNode node : nodes) {
                                Parameter[] parameters = node.getParameters();
                                if (node.getReturnType() != VOID_TYPE && (parameters == null || parameters.length == 0)) {
                                    if (visitor != null) visitor.visitMethod(node);
                                    storeType(pexp, READONLY_PROPERTY_RETURN);
                                    return true;
                                }
                            }
                        }
                        if (pluginFactory!=null) {
                            TypeCheckerPlugin plugin = pluginFactory.getTypeCheckerPlugin(classNode);
                            if (plugin!=null) {
                                PropertyNode result = plugin.resolveProperty(current, propertyName);
                                if (result!=null) {
                                    if (visitor != null) visitor.visitProperty(result);
                                    storeType(pexp, result.getType());
                                    return true;
                                }
                            }
                        }
                        // if the property expression is an attribute expression (o.@attr), then
                        // we stop now, otherwise we must check the parent class
                        current = isAttributeExpression ? null : current.getSuperClass();
                    }
                }
            } else {
                if (visitor!=null) {
                    // todo : type inferrence on maps and lists, if possible
                    PropertyNode node = new PropertyNode(propertyName, Opcodes.ACC_PUBLIC, OBJECT_TYPE, clazz, null, null, null);
                    visitor.visitProperty(node);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void visitForLoop(final ForStatement forLoop) {
        Map<VariableExpression, List<ClassNode>> oldTracker = pushAssignmentTracking();
        final ClassNode collectionType = getType(forLoop.getCollectionExpression());
        ClassNode componentType = collectionType.getComponentType();
        if (componentType == null) {
            if (collectionType.implementsInterface(ITERABLE_TYPE)) {
                ClassNode intf = GenericsUtils.parameterizeInterfaceGenerics(collectionType, ITERABLE_TYPE);
                GenericsType[] genericsTypes = intf.getGenericsTypes();
                componentType = genericsTypes[0].getType();
            } else if (collectionType == ClassHelper.STRING_TYPE) {
                componentType = ClassHelper.Character_TYPE;
            } else {
                componentType = ClassHelper.OBJECT_TYPE;
            }
        }
        forLoopVariableTypes.put(forLoop.getVariable(), componentType);
        if (!checkCompatibleAssignmentTypes(forLoop.getVariableType(), componentType)) {
            addStaticTypeError("Cannot loop with element of type " + forLoop.getVariableType() + " with collection of type " + collectionType, forLoop);
        }
        try {
            super.visitForLoop(forLoop);
        } finally {
            forLoopVariableTypes.remove(forLoop.getVariable());
        }
        popAssignmentTracking(oldTracker);
    }

    @Override
    public void visitWhileLoop(final WhileStatement loop) {
        Map<VariableExpression, List<ClassNode>> oldTracker = pushAssignmentTracking();
        super.visitWhileLoop(loop);
        popAssignmentTracking(oldTracker);
    }

    @Override
    public void visitBitwiseNegationExpression(BitwiseNegationExpression expression) {
        super.visitBitwiseNegationExpression(expression);
        ClassNode type = getType(expression);
        ClassNode typeRe = type.redirect();
        ClassNode resultType;
        if (isBigIntCategory(typeRe)) {
            // allow any internal number that is not a floating point one
            resultType = type;
        } else if (typeRe == STRING_TYPE || typeRe == GSTRING_TYPE) {
            resultType = PATTERN_TYPE;
        } else if (typeRe == ArrayList_TYPE) {
            resultType = ArrayList_TYPE;
        } else {
            MethodNode mn = findMethodOrFail(expression, type, "bitwiseNegate");
            resultType = mn.getReturnType();
        }
        storeType(expression, resultType);
    }

    @Override
    public void visitUnaryPlusExpression(UnaryPlusExpression expression) {
        super.visitUnaryPlusExpression(expression);
        negativeOrPositiveUnary(expression, "positive");
    }

    @Override
    public void visitUnaryMinusExpression(UnaryMinusExpression expression) {
        super.visitUnaryMinusExpression(expression);
        negativeOrPositiveUnary(expression, "negative");
    }

    private void negativeOrPositiveUnary(Expression expression, String name) {
        ClassNode type = getType(expression);
        ClassNode typeRe = type.redirect();
        ClassNode resultType;
        if (isBigDecCategory(typeRe)) {
            resultType = type;
        } else if (typeRe == ArrayList_TYPE) {
            resultType = ArrayList_TYPE;
        } else {
            MethodNode mn = findMethodOrFail(expression, type, name);
            resultType = mn.getReturnType();
        }
        storeType(expression, resultType);
    }

    @Override
    protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
        MethodNode old = this.methodNode;
		this.methodNode = node;
        super.visitConstructorOrMethod(node, isConstructor);
        if (!isConstructor) {
			returnAdder.visitMethod(node);
		}
        this.methodNode = old;
    }

    @Override
    public void visitReturnStatement(ReturnStatement statement) {
        super.visitReturnStatement(statement);
        checkReturnType(statement);
        if (closureExpression!=null && statement.getExpression()!=ConstantExpression.NULL) {
            addClosureReturnType(getType(statement.getExpression()));
        }
    }

    private ClassNode checkReturnType(final ReturnStatement statement) {
        ClassNode type = getType(statement.getExpression());
        if (methodNode != null) {
            if (!methodNode.isVoidMethod() 
					&& !type.equals(void_WRAPPER_TYPE) 
					&& !type.equals(VOID_TYPE)
					&& !checkCompatibleAssignmentTypes(methodNode.getReturnType(), type)) {
                addStaticTypeError("Cannot return value of type " + type + " on method returning type " + methodNode.getReturnType(), statement.getExpression());
            }
        }
        return type;
    }

    private void addClosureReturnType(ClassNode returnType) {
        if (closureReturnTypes==null) closureReturnTypes = new LinkedList<ClassNode>();
        closureReturnTypes.add(returnType);
    }

    @Override
    public void visitConstructorCallExpression(ConstructorCallExpression call) {
        super.visitConstructorCallExpression(call);
        ClassNode receiver = call.isThisCall()?classNode:
                call.isSuperCall()?classNode.getSuperClass():call.getType();
        ClassNode[] args = getArgumentTypes(InvocationWriter.makeArgumentList(call.getArguments()));
        MethodNode node = findMethodOrFail(call, receiver, "<init>", args);
        if (node!=null) {
            storeTargetMethod(call, node);
        }
    }

    private ClassNode[] getArgumentTypes(ArgumentListExpression args) {
        List<Expression> arglist = args.getExpressions();
        ClassNode[] ret = new ClassNode[arglist.size()];
        int i = 0;
        for (Expression exp : arglist) {
            if (exp instanceof ConstantExpression && ((ConstantExpression)exp).getValue()==null) {
                ret[i] = UNKNOWN_PARAMETER_TYPE;
            } else {
                ret[i] = getType(exp);
            }
            i++;
        }
        return ret;
    }

    @Override
    public void visitClosureExpression(final ClosureExpression expression) {
		// first, collect closure shared variables and reinitialize types
		SharedVariableCollector collector = new SharedVariableCollector(getSourceUnit());
		collector.visitClosureExpression(expression);
		Set<VariableExpression> closureSharedExpressions = collector.getClosureSharedExpressions();
		Map<VariableExpression, ListHashMap> typesBeforeVisit = null;
		if (!closureSharedExpressions.isEmpty()) {
			typesBeforeVisit = new HashMap<VariableExpression, ListHashMap>();
			saveVariableExpressionMetadata(closureSharedExpressions, typesBeforeVisit);
		}

		// perform visit
        ClosureExpression oldClosureExpr = closureExpression;
        List<ClassNode> oldClosureReturnTypes = closureReturnTypes;
        closureExpression = expression;
        super.visitClosureExpression(expression);
        MethodNode node = new MethodNode("dummy", 0, ClassHelper.OBJECT_TYPE, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, expression.getCode());
        closureReturnAdder.visitMethod(node);

        if (closureReturnTypes!=null) {
            expression.putNodeMetaData(StaticTypesMarker.INFERRED_RETURN_TYPE, lowestUpperBound(closureReturnTypes));
        }

        closureExpression = oldClosureExpr;
        closureReturnTypes = oldClosureReturnTypes;
		
		// restore original metadata
		restoreVariableExpressionMetadata(typesBeforeVisit);
	}

	private void restoreVariableExpressionMetadata(final Map<VariableExpression, ListHashMap> typesBeforeVisit) {
		if (typesBeforeVisit!=null) {
			for (Map.Entry<VariableExpression, ListHashMap> entry : typesBeforeVisit.entrySet()) {
				VariableExpression ve = entry.getKey();
				ListHashMap metadata = entry.getValue();
				for (StaticTypesMarker marker : StaticTypesMarker.values()) {
					ve.removeNodeMetaData(marker);
					Object value = metadata.get(marker);
					if (value!=null) ve.setNodeMetaData(marker, value);
				}
			}
		}
	}

	private void saveVariableExpressionMetadata(final Set<VariableExpression> closureSharedExpressions, final Map<VariableExpression, ListHashMap> typesBeforeVisit) {
		for (VariableExpression ve : closureSharedExpressions) {
			ListHashMap<StaticTypesMarker,Object> metadata = new ListHashMap<StaticTypesMarker, Object>();
			for (StaticTypesMarker marker : StaticTypesMarker.values()) {
				Object value = ve.getNodeMetaData(marker);
				if (value!=null) {
					metadata.put(marker, value);
				}
			}
			typesBeforeVisit.put(ve, metadata);
			Variable accessedVariable = ve.getAccessedVariable();
			if (accessedVariable!=ve && accessedVariable instanceof VariableExpression) {
				saveVariableExpressionMetadata(Collections.singleton((VariableExpression)accessedVariable), typesBeforeVisit);
			}
		}
	}

	@Override
    public void visitMethod(final MethodNode node) {
        // alreadyVisitedMethods prevents from visiting the same method multiple times
        // and prevents from infinite loops
        if (alreadyVisitedMethods.contains(node)) return;
        alreadyVisitedMethods.add(node);

        // second, we must ensure that this method MUST be statically checked
        // for example, in a mixed mode where only some methods are statically checked
        // we must not visit a method which used dynamic dispatch.
        // We do not check for an annotation because some other AST transformations
        // may use this visitor without the annotation being explicitely set
        if (!methodsToBeVisited.isEmpty() && !methodsToBeVisited.contains(node)) return;
        super.visitMethod(node);
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression call) {
        final String name = call.getMethodAsString();
        if (name == null) {
            addStaticTypeError("cannot resolve dynamic method name at compile time.", call.getMethod());
            return;
        }

        final Expression objectExpression = call.getObjectExpression();

        objectExpression.visit(this);
        call.getMethod().visit(this);

        // if the call expression is a spread operator call, then we must make sure that
        // the call is made on a collection type
        if (call.isSpreadSafe()) {
            ClassNode expressionType = getType(objectExpression);
            if (!(expressionType.equals(Collection_TYPE)||expressionType.implementsInterface(Collection_TYPE))) {
                addStaticTypeError("Spread operator can only be used on collection types", expressionType);
                return;
            } else {
                // type check call as if it was made on component type
                ClassNode componentType = inferComponentType(expressionType);
                MethodCallExpression subcall = new MethodCallExpression(
                        new CastExpression(componentType, EmptyExpression.INSTANCE),
                        name,
                        call.getArguments()
                );
                subcall.setLineNumber(call.getLineNumber());
                subcall.setColumnNumber(call.getColumnNumber());
                visitMethodCallExpression(subcall);
                // the inferred type here should be a list of what the subcall returns
                ClassNode subcallReturnType = getType(subcall);
                ClassNode listNode = new ClassNode(List.class);
                listNode.setGenericsTypes(new GenericsType[]{new GenericsType(wrapTypeIfNecessary(subcallReturnType))});
                storeType(call, listNode);
                return;
            }
        }

        final ClassNode rememberLastItType = lastImplicitItType;
        Expression callArguments = call.getArguments();

        boolean isWithCall = isWithCall(name, callArguments);

        if (!isWithCall) {
            // if it is not a "with" call, arguments should be visited first
            callArguments.visit(this);
        }

        ClassNode[] args = getArgumentTypes(InvocationWriter.makeArgumentList(callArguments));
        final boolean isCallOnClosure = isClosureCall(name, objectExpression);
        final ClassNode receiver = getType(objectExpression);

        if (isWithCall) {
            withReceiverList.add(0, receiver); // must be added first in the list
            lastImplicitItType = receiver;
            // if the provided closure uses an explicit parameter definition, we can
            // also check that the provided type is correct
            if (callArguments instanceof ArgumentListExpression) {
                ArgumentListExpression argList = (ArgumentListExpression) callArguments;
                ClosureExpression closure = (ClosureExpression) argList.getExpression(0);
                Parameter[] parameters = closure.getParameters();
                if (parameters.length > 1) {
                    addStaticTypeError("Unexpected number of parameters for a with call", argList);
                } else if (parameters.length == 1) {
                    Parameter param = parameters[0];
                    if (!param.isDynamicTyped() && !isAssignableTo(receiver, param.getType().redirect())) {
                        addStaticTypeError("Expected parameter type: " + receiver.toString(false) + " but was: " + param.getType().redirect().toString(false), param);
                    }
                }
            }
        }

        try {
            if (isWithCall) {
                // in case of a with call, arguments (the closure) should be visited now that we checked
                // the arguments
                callArguments.visit(this);
            }

            // method call receivers are :
            //   - possible "with" receivers
            //   - the actual receiver as found in the method call expression
            //   - any of the potential receivers found in the instanceof temporary table
            // in that order
            List<ClassNode> receivers = new LinkedList<ClassNode>();
            if (!withReceiverList.isEmpty()) receivers.addAll(withReceiverList);
            receivers.add(receiver);
            if (objectExpression instanceof ClassExpression) {
                receivers.add(CLASS_Type);
            }
            if (!temporaryIfBranchTypeInformation.empty()) {
                final Map<Object, List<ClassNode>> tempo = temporaryIfBranchTypeInformation.peek();
                Object key = extractTemporaryTypeInfoKey(objectExpression);
                List<ClassNode> potentialReceiverType = tempo.get(key);
                if (potentialReceiverType != null) receivers.addAll(potentialReceiverType);
            }
            List<MethodNode> mn = null;
            ClassNode chosenReceiver = null;
            for (ClassNode currentReceiver : receivers) {
                mn = findMethod(currentReceiver, name, args);
                if (!mn.isEmpty()) {
                    typeCheckMethodsWithGenerics(currentReceiver, args, mn, call);
                    chosenReceiver = currentReceiver;
                    break;
                }
            }
            if (mn.isEmpty()) {
                addStaticTypeError("Cannot find matching method " + receiver.getName() + "#" + toMethodParametersString(name, args), call);
            } else {
                if (isCallOnClosure) {
                    // this is a closure.call() call
                    if (objectExpression instanceof VariableExpression) {
                        Variable variable = findTargetVariable((VariableExpression)objectExpression);
                        if (variable instanceof Expression) {
                            Object data = ((Expression) variable).getNodeMetaData(StaticTypesMarker.CLOSURE_ARGUMENTS);
                            if (data!=null) {
                                Parameter[] parameters = (Parameter[]) data;
                                typeCheckClosureCall(callArguments, args, parameters);
                            }
                            Object type = ((Expression) variable).getNodeMetaData(StaticTypesMarker.INFERRED_RETURN_TYPE);
                            if (type!=null) {
                                 storeType(call, (ClassNode) type);
                            }
                        }
                    } else if (objectExpression instanceof ClosureExpression) {
                        // we can get actual parameters directly
                        Parameter[] parameters = ((ClosureExpression)objectExpression).getParameters();
                        typeCheckClosureCall(callArguments, args, parameters);
                        Object data = objectExpression.getNodeMetaData(StaticTypesMarker.INFERRED_RETURN_TYPE);
                        if (data!=null) {
                            storeType(call, (ClassNode) data);
                        }
                    }
                } else {
                    if (mn.size()==1) {
                        MethodNode directMethodCallCandidate = mn.get(0);
                        // visit the method to obtain inferred return type
                        ClassNode currentClassNode = classNode;
                        classNode = directMethodCallCandidate.getDeclaringClass();
                        visitMethod(directMethodCallCandidate);
                        classNode = currentClassNode;
                        ClassNode returnType = getType(directMethodCallCandidate);
                        if (returnType.isUsingGenerics()) {
                            returnType = inferReturnTypeGenerics(chosenReceiver, directMethodCallCandidate, callArguments);
                        }
                        storeType(call, returnType);
                        storeTargetMethod(call, directMethodCallCandidate);

						// if the object expression is a closure shared variable, we will have to perform a second pass
						if (objectExpression instanceof VariableExpression) {
							VariableExpression var = (VariableExpression) objectExpression;
							if (var.isClosureSharedVariable()) secondPassExpressions.add(call);
						}

                    } else {
                        addStaticTypeError("Reference to method is ambiguous. Cannot choose between "+mn, call);
                    }
                }
            }
        } finally {
            if (isWithCall) {
                lastImplicitItType = rememberLastItType;
                withReceiverList.removeFirst();
            }
        }
    }

    private void storeTargetMethod(final Expression call, final MethodNode directMethodCallCandidate) {
        call.putNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET, directMethodCallCandidate);
    }

    private boolean isClosureCall(final String name, final Expression objectExpression) {
        if (!"call".equals(name)) return false;
        if (objectExpression instanceof ClosureExpression) return true;
        return (getType(objectExpression).equals(CLOSURE_TYPE));
    }

    private void typeCheckClosureCall(final Expression callArguments, final ClassNode[] args, final Parameter[] parameters) {
        if (allParametersAndArgumentsMatch(parameters, args)<0 &&
            lastArgMatchesVarg(parameters, args)<0) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0, parametersLength = parameters.length; i < parametersLength; i++) {
                final Parameter parameter = parameters[i];
                sb.append(parameter.getType().getName());
                if (i<parametersLength-1) sb.append(", ");
            }
            sb.append("]");
            addStaticTypeError("Closure argument types: "+sb+" do not match with parameter types: "+ Arrays.toString(args), callArguments);
        }
    }

    @Override
    public void visitIfElse(final IfStatement ifElse) {
        Map<VariableExpression, List<ClassNode>> oldTracker = pushAssignmentTracking();

        try {
            // create a new temporary element in the if-then-else type info
            pushTemporaryTypeInfo();
            visitStatement(ifElse);
            ifElse.getBooleanExpression().visit(this);
            ifElse.getIfBlock().visit(this);

            // pop if-then-else temporary type info
            temporaryIfBranchTypeInformation.pop();

            Statement elseBlock = ifElse.getElseBlock();
            if (elseBlock instanceof EmptyStatement) {
                // dispatching to EmptyStatement will not call back visitor,
                // must call our visitEmptyStatement explicitly
                visitEmptyStatement((EmptyStatement) elseBlock);
            } else {
                elseBlock.visit(this);
            }
        } finally {
            popAssignmentTracking(oldTracker);
        }
    }

    private void popAssignmentTracking(final Map<VariableExpression, List<ClassNode>> oldTracker) {
        if (!ifElseForWhileAssignmentTracker.isEmpty()) {
            for (Map.Entry<VariableExpression, List<ClassNode>> entry : ifElseForWhileAssignmentTracker.entrySet()) {
                storeType(entry.getKey(), lowestUpperBound(entry.getValue()));
            }
        }
        ifElseForWhileAssignmentTracker = oldTracker;
    }

    private Map<VariableExpression, List<ClassNode>> pushAssignmentTracking() {
        // memorize current assignment context
        Map<VariableExpression,List<ClassNode>> oldTracker = ifElseForWhileAssignmentTracker;
        ifElseForWhileAssignmentTracker = new HashMap<VariableExpression, List<ClassNode>>();
        return oldTracker;
    }

    @Override
    public void visitCastExpression(final CastExpression expression) {
        super.visitCastExpression(expression);
        if (!expression.isCoerce()) {
            ClassNode targetType = expression.getType();
            Expression source = expression.getExpression();
            boolean sourceIsNull = source instanceof ConstantExpression && ((ConstantExpression) source).getValue()==null;
            ClassNode expressionType = getType(source);
            if (targetType.equals(char_TYPE) && expressionType==STRING_TYPE
                    && source instanceof ConstantExpression && source.getText().length()==1) {
                // ex: (char) 'c'
            } else if (targetType.equals(Character_TYPE) && (expressionType==STRING_TYPE||sourceIsNull)
                    && (sourceIsNull || source instanceof ConstantExpression && source.getText().length()==1)) {
                // ex : (Character) 'c'
            } else if (isNumberCategory(getWrapper(targetType)) && isNumberCategory(getWrapper(expressionType))) {
                // ex: short s = (short) 0
            } else if (sourceIsNull && !isPrimitiveType(targetType)) {
                // ex: (Date)null
            } else if (!isAssignableTo(expressionType, targetType)) {
                addStaticTypeError("Inconvertible types: cannot cast "+expressionType.getName()+" to "+targetType.getName(), expression);
            }
        }
        storeType(expression, expression.getType());
    }

    @Override
    public void visitTernaryExpression(final TernaryExpression expression) {
        Map<VariableExpression, List<ClassNode>> oldTracker = pushAssignmentTracking();
        // create a new temporary element in the if-then-else type info
        pushTemporaryTypeInfo();
        expression.getBooleanExpression().visit(this);
        expression.getTrueExpression().visit(this);
        // pop if-then-else temporary type info
        temporaryIfBranchTypeInformation.pop();
        expression.getFalseExpression().visit(this);
        // store type information
        final ClassNode typeOfTrue = getType(expression.getTrueExpression());
        final ClassNode typeOfFalse = getType(expression.getFalseExpression());
        storeType(expression, lowestUpperBound(typeOfTrue, typeOfFalse));
        popAssignmentTracking(oldTracker);
    }

    private void pushTemporaryTypeInfo() {
        Map<Object, List<ClassNode>> potentialTypes = new HashMap<Object, List<ClassNode>>();
        temporaryIfBranchTypeInformation.push(potentialTypes);
    }


    private void storeType(Expression exp, ClassNode cn) {
        ClassNode oldValue = (ClassNode) exp.putNodeMetaData(StaticTypesMarker.INFERRED_TYPE, cn);
        if (oldValue!=null) {
            // this may happen when a variable declaration type is wider than the subsequent assignment values
            // for example :
            // def o = 1 // first, an int
            // o = 'String' // then a string
            // o = new Object() // and eventually an object !
            // in that case, the INFERRED_TYPE corresponds to the current inferred type, while
            // DECLARATION_INFERRED_TYPE is the type which should be used for the initial type declaration
            ClassNode oldDIT = (ClassNode) exp.getNodeMetaData(StaticTypesMarker.DECLARATION_INFERRED_TYPE);
            if (oldDIT!=null) {
                exp.putNodeMetaData(StaticTypesMarker.DECLARATION_INFERRED_TYPE, lowestUpperBound(oldDIT, cn));
            } else {
                exp.putNodeMetaData(StaticTypesMarker.DECLARATION_INFERRED_TYPE, lowestUpperBound(oldValue, cn));
            }
        }
        if (exp instanceof VariableExpression) {
			VariableExpression var = (VariableExpression) exp;
			final Variable accessedVariable = var.getAccessedVariable();
            if (accessedVariable != null && accessedVariable != exp && accessedVariable instanceof VariableExpression) {
                storeType((Expression) accessedVariable, cn);
            }
			if (var.isClosureSharedVariable()) {
				List<ClassNode> assignedTypes = closureSharedVariablesAssignmentTypes.get(var);
				if (assignedTypes==null) {
					assignedTypes = new LinkedList<ClassNode>();
					closureSharedVariablesAssignmentTypes.put(var, assignedTypes);
				}
				assignedTypes.add(cn);
			}

        }
    }

    private ClassNode getResultType(ClassNode left, int op, ClassNode right, BinaryExpression expr) {
        ClassNode leftRedirect = left.redirect();
        ClassNode rightRedirect = right.redirect();

        Expression leftExpression = expr.getLeftExpression();
        if (op == ASSIGN) {
            if (leftRedirect.isArray() && !rightRedirect.isArray()) return leftRedirect;
            if (leftRedirect.implementsInterface(Collection_TYPE) && rightRedirect.implementsInterface(Collection_TYPE)) {
                // because of type inferrence, we must perform an additional check if the right expression
                // is an empty list expression ([]). In that case and only in that case, the inferred type
                // will be wrong, so we will prefer the left type
                if (expr.getRightExpression() instanceof ListExpression) {
                    List<Expression> list = ((ListExpression) expr.getRightExpression()).getExpressions();
                    if (list.isEmpty()) return left;
                }
                return right;
            }
            if (rightRedirect.implementsInterface(Collection_TYPE) && rightRedirect.isDerivedFrom(leftRedirect)) {
                // ex : def foos = ['a','b','c']
                return right;
            }
            if (leftExpression instanceof VariableExpression) {
                VariableExpression target = (VariableExpression) leftExpression;
                if (target.getAccessedVariable() instanceof VariableExpression && target.getAccessedVariable()!=leftExpression) {
                    target = (VariableExpression) target.getAccessedVariable();
                }
                ClassNode initialType = target.getType().redirect();
                // as anything can be assigned to a String, Class or boolean, return the left type instead
                if (STRING_TYPE.equals(initialType)
                        || CLASS_Type.equals(initialType)
                        || Boolean_TYPE.equals(initialType)
                        || isPrimitiveType(initialType)
                        || BigDecimal_TYPE==initialType
                        || BigInteger_TYPE==initialType) {
                    return initialType;
                }
            }
            return right;
        } else if (isBoolIntrinsicOp(op)) {
            return boolean_TYPE;
        } else if (isArrayOp(op)) {
            if (ClassHelper.STRING_TYPE.equals(left)) {
                // special case here
                return ClassHelper.STRING_TYPE;
            }
            return inferComponentType(left);
        } else if (op == FIND_REGEX) {
            // this case always succeeds the result is a Matcher
            return Matcher_TYPE;
        }
        // the left operand is determining the result of the operation
        // for primitives and their wrapper we use a fixed table here
        else if (isNumberType(leftRedirect) && isNumberType(rightRedirect)) {
            if (isOperationInGroup(op)) {
                if (isIntCategory(leftRedirect) && isIntCategory(rightRedirect)) return int_TYPE;
                if (isLongCategory(leftRedirect) && isLongCategory(rightRedirect)) return long_TYPE;
                if (isFloat(leftRedirect) && isFloat(rightRedirect)) return float_TYPE;
                if (isDouble(leftRedirect) && isDouble(rightRedirect)) return double_TYPE;
            } else if (isPowerOperator(op)) {
                return Number_TYPE;
            } else if (isBitOperator(op)) {
                if (isIntCategory(leftRedirect) && isIntCategory(rightRedirect)) return int_TYPE;
                if (isLongCategory(leftRedirect) && isLongCategory(rightRedirect)) return Long_TYPE;
                if (isBigIntCategory(leftRedirect) && isBigIntCategory(rightRedirect)) return BigInteger_TYPE;
            } else if (isCompareToBoolean(op) || op==COMPARE_EQUAL) {
                return boolean_TYPE;
            }
        }


        // try to find a method for the operation
        String operationName = getOperationName(op);
        if (isShiftOperation(operationName) && isNumberCategory(leftRedirect) && (isIntCategory(rightRedirect) || isLongCategory(rightRedirect))) {
            return leftRedirect;
        }

        // Divisions may produce different results depending on operand types
        if (DIVIDE==op || DIVIDE_EQUAL==op) {
            if (isFloatingCategory(leftRedirect) || isFloatingCategory(rightRedirect)) {
                return Double_TYPE;
            } else if (BigDecimal_TYPE.equals(leftRedirect)||BigDecimal_TYPE.equals(rightRedirect)) {
                return BigDecimal_TYPE;
            }
        } else if (isOperationInGroup(op)) {
            if (isNumberCategory(getWrapper(leftRedirect)) && isNumberCategory(getWrapper(rightRedirect))) {
                return getGroupOperationResultType(leftRedirect, rightRedirect);
            }
        }

        MethodNode method = findMethodOrFail(expr, leftRedirect, operationName, rightRedirect);
        if (method != null) {
            if (isCompareToBoolean(op)) return boolean_TYPE;
            if (op == COMPARE_TO) return int_TYPE;
            return getType(method);
        }
        //TODO: other cases
        return null;
    }

    private static ClassNode getGroupOperationResultType(ClassNode a, ClassNode b) {
        if (isBigIntCategory(a) && isBigIntCategory(b)) return BigInteger_TYPE;
        if (isBigDecCategory(a) && isBigDecCategory(b)) return BigDecimal_TYPE;        
        if (BigDecimal_TYPE.equals(a)||BigDecimal_TYPE.equals(b)) return BigDecimal_TYPE;
        if (BigInteger_TYPE.equals(a)||BigInteger_TYPE.equals(b)) {
            if (isBigIntCategory(a) && isBigIntCategory(b)) return BigInteger_TYPE;
            return BigDecimal_TYPE;
        }
        if (double_TYPE.equals(a) || double_TYPE.equals(b)) return double_TYPE;
        if (Double_TYPE.equals(a) || Double_TYPE.equals(b)) return Double_TYPE;
        if (float_TYPE.equals(a) || float_TYPE.equals(b)) return float_TYPE;
        if (Float_TYPE.equals(a) || Float_TYPE.equals(b)) return Float_TYPE;
        if (long_TYPE.equals(a) || long_TYPE.equals(b)) return long_TYPE;
        if (Long_TYPE.equals(a) || Long_TYPE.equals(b)) return Long_TYPE;
        if (int_TYPE.equals(a) || int_TYPE.equals(b)) return int_TYPE;
        if (Integer_TYPE.equals(a) || Integer_TYPE.equals(b)) return Integer_TYPE;
        if (short_TYPE.equals(a) || short_TYPE.equals(b)) return short_TYPE;
        if (Short_TYPE.equals(a) || Short_TYPE.equals(b)) return Short_TYPE;
        if (byte_TYPE.equals(a) || byte_TYPE.equals(b)) return byte_TYPE;
        if (Byte_TYPE.equals(a) || Byte_TYPE.equals(b)) return Byte_TYPE;
        if (char_TYPE.equals(a) || char_TYPE.equals(b)) return char_TYPE;
        if (Character_TYPE.equals(a) || Character_TYPE.equals(b)) return Character_TYPE;
        return Number_TYPE;
    }
    
    private ClassNode inferComponentType(final ClassNode containerType) {
        final ClassNode componentType = containerType.getComponentType();
        if (componentType == null) {
            // check if any generic information could help
            GenericsType[] types = containerType.getGenericsTypes();
            if (types != null && types.length == 1) {
                return types[0].getType();
            }
            return OBJECT_TYPE;
        } else {
            return componentType;
        }
    }

    private MethodNode findMethodOrFail(
            Expression expr,
            ClassNode receiver, String name, ClassNode... args) {
        final List<MethodNode> methods = findMethod(receiver, name, args);
        if (methods.isEmpty()) {
            addStaticTypeError("Cannot find matching method " + receiver.getName() + "#" + toMethodParametersString(name, args), expr);
        } else if (methods.size()==1) {
            return methods.get(0);
        } else {
            addStaticTypeError("Reference to method is ambiguous. Cannot choose between "+methods, expr);
        }
        return null;
    }

    private List<MethodNode> findMethod(
            ClassNode receiver, String name, ClassNode... args) {
        if (isPrimitiveType(receiver)) receiver=getWrapper(receiver);
        List<MethodNode> methods;
        if ("<init>".equals(name)) {
            methods = new ArrayList<MethodNode>(receiver.getDeclaredConstructors());
            if (methods.isEmpty()) {
                MethodNode node = new MethodNode("<init>", Opcodes.ACC_PUBLIC, receiver, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, EmptyStatement.INSTANCE);
                node.setDeclaringClass(receiver);
                return Collections.singletonList(node);
            }
        } else {
            methods = receiver.getMethods(name);
            if (methods.isEmpty() && args==null || args.length==0) {
                // check if it's a property
                String pname = null;
                if (name.startsWith("get")) {
                    pname = java.beans.Introspector.decapitalize(name.substring(3));
                } else if (name.startsWith("is")) {
                    pname  = java.beans.Introspector.decapitalize(name.substring(2));
                }
                if (pname!=null) {
                    // we don't use property exists there because findMethod is called on super clases recursively
                    PropertyNode property = receiver.getProperty(pname);
                    if (property!=null) {
                        return Collections.singletonList(
                                new MethodNode(name, Opcodes.ACC_PUBLIC, property.getType(), Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, EmptyStatement.INSTANCE));

                    }
                }
            }
        }


        List<MethodNode> chosen = chooseBestMethod(receiver, methods, args);
        if (!chosen.isEmpty()) return chosen;
        // perform a lookup in DGM methods
        methods.clear();
        chosen = findDGMMethodsByNameAndArguments(receiver, name, args, methods);
        if (!chosen.isEmpty()) {
            return chosen;
        }

        if (receiver == ClassHelper.GSTRING_TYPE) return findMethod(ClassHelper.STRING_TYPE, name, args);
        
        if (pluginFactory!=null) {
            TypeCheckerPlugin plugin = pluginFactory.getTypeCheckerPlugin(classNode);
            if (plugin!=null) {
                List<MethodNode> methodNodes = plugin.findMethod(receiver, name, args);
                if (methodNodes!=null && !methodNodes.isEmpty()) return methodNodes;
            }
        }
        
        return EMPTY_METHODNODE_LIST;
    }

    private List<MethodNode> findDGMMethodsByNameAndArguments(final ClassNode receiver, final String name, final ClassNode[] args, final List<MethodNode> methods) {
        final List<MethodNode> chosen;
        methods.addAll(findDGMMethodsForClassNode(receiver, name));

        chosen = chooseBestMethod(receiver, methods, args);
            return chosen;
        }

    /**
     * Given a list of candidate methods, returns the one which best matches the argument types
     *
     * @param receiver
     * @param methods candidate methods
     * @param args argument types
     * @return the list of methods which best matches the argument types. It is still possible that multiple
     * methods match the argument types.
     */
    private List<MethodNode> chooseBestMethod(final ClassNode receiver, Collection<MethodNode> methods, ClassNode... args) {
        if (methods.isEmpty()) return Collections.emptyList();
        List<MethodNode> bestChoices = new LinkedList<MethodNode>();
        int bestDist = Integer.MAX_VALUE;
        for (MethodNode m : methods) {
            // todo : corner case
            /*
                class B extends A {}

                Animal foo(A o) {...}
                Person foo(B i){...}

                B  a = new B()
                Person p = foo(b)
             */

            Parameter[] params = parameterizeArguments(receiver, m);
            if (params.length == args.length) {
                int allPMatch = allParametersAndArgumentsMatch(params, args);
                int lastArgMatch = isVargs(params)?lastArgMatchesVarg(params, args):-1;
                if (lastArgMatch>=0) lastArgMatch++; // ensure exact matches are preferred over vargs
                int dist = allPMatch>=0?Math.max(allPMatch, lastArgMatch):lastArgMatch;
                if (dist>=0 && !receiver.equals(m.getDeclaringClass())) dist++;
                if (dist>=0 && dist<bestDist) {
                    bestChoices.clear();
                    bestChoices.add(m);
                    bestDist = dist;
                } else if (dist>=0 && dist==bestDist) {
                    bestChoices.add(m);
                }
            } else if (isVargs(params)) {
                // there are three case for vargs
                // (1) varg part is left out
                if (params.length == args.length + 1) {
                    if (bestDist>1) {
                        bestChoices.clear();
                        bestChoices.add(m);
                        bestDist = 1;
                    }
                } else {
                    // (2) last argument is put in the vargs array
                    //      that case is handled above already
                    // (3) there is more than one argument for the vargs array
                    int dist = excessArgumentsMatchesVargsParameter(params, args);
                    if (dist >= 0 && !receiver.equals(m.getDeclaringClass())) dist++;
                    // varargs methods must not be preferred to methods without varargs
                    // for example :
                    // int sum(int x) should be preferred to int sum(int x, int... y)
                    dist++;
                    if (params.length < args.length && dist >= 0) {
                        if (dist >= 0 && dist < bestDist) {
                            bestChoices.clear();
                            bestChoices.add(m);
                            bestDist = dist;
                        } else if (dist >= 0 && dist == bestDist) {
                            bestChoices.add(m);
                        }
                    }
                }
            }
        }
        return bestChoices;
    }

    /**
     * Given a receiver and a method node, parameterize the method arguments using
     * available generic type information.
     * @param receiver
     * @param m
     * @return
     */
    private Parameter[] parameterizeArguments(final ClassNode receiver, final MethodNode m) {
        GenericsType[] redirectReceiverTypes = receiver.redirect().getGenericsTypes();
        if (redirectReceiverTypes==null) {
            // we must perform an additional check for methods like Collections#sort which define generics
            // at the method level
            redirectReceiverTypes = m.getGenericsTypes();
        }
        if (redirectReceiverTypes==null) return m.getParameters();
        Parameter[] methodParameters = m.getParameters();
        Parameter[] params = new Parameter[methodParameters.length];
        GenericsType[] receiverParameterizedTypes = receiver.getGenericsTypes();
        if (receiverParameterizedTypes==null) {
            receiverParameterizedTypes = redirectReceiverTypes;
        }
        for (int i = 0; i < methodParameters.length; i++) {
            Parameter methodParameter = methodParameters[i];
            ClassNode paramType = methodParameter.getType();
            if (paramType.isUsingGenerics()) {
                GenericsType[] alignmentTypes = paramType.getGenericsTypes();
                GenericsType[] genericsTypes = GenericsUtils.alignGenericTypes(redirectReceiverTypes, receiverParameterizedTypes, alignmentTypes);
                if (genericsTypes.length==1) {
                    ClassNode parameterizedCN;
                    if (paramType.equals(OBJECT_TYPE)) {
                        parameterizedCN = genericsTypes[0].getType();
                    } else {
                        parameterizedCN= paramType.getPlainNodeReference();
                        parameterizedCN.setGenericsTypes(genericsTypes);
                    }
                    params[i] = new Parameter(
                            parameterizedCN,
                            methodParameter.getName()
                    );
                } else {
                    params[i] = methodParameter;
                }
            } else {
                params[i] = methodParameter;
            }
        }
        return params;
    }

    private ClassNode getType(ASTNode exp) {
        ClassNode cn = (ClassNode) exp.getNodeMetaData(StaticTypesMarker.INFERRED_TYPE);
        if (cn != null) return cn;
        if (exp instanceof VariableExpression) {
            VariableExpression vexp = (VariableExpression) exp;
            if (vexp == VariableExpression.THIS_EXPRESSION) return classNode;
            if (vexp == VariableExpression.SUPER_EXPRESSION) return classNode.getSuperClass();
            final Variable variable = vexp.getAccessedVariable();
            if (variable != null && variable != vexp && variable instanceof VariableExpression) {
                return getType((Expression) variable);
            }
            if (variable instanceof Parameter) {
                Parameter parameter = (Parameter) variable;
                ClassNode type = forLoopVariableTypes.get(parameter);
                if (type!=null) return type;
            }
        } else if (exp instanceof PropertyExpression) {
            PropertyExpression pexp = (PropertyExpression) exp;
            ClassNode objectExpType = getType(pexp.getObjectExpression());
            if ((LIST_TYPE.equals(objectExpType)|| objectExpType.implementsInterface(LIST_TYPE)) && pexp.isSpreadSafe()) {
                // list*.property syntax
                // todo : type inferrence on list content when possible
                return LIST_TYPE;
            } else if ((objectExpType.equals(MAP_TYPE) || objectExpType.implementsInterface(MAP_TYPE)) && pexp.isSpreadSafe()) {
                // map*.property syntax
                // only "key" and "value" are allowed
                String propertyName = pexp.getPropertyAsString();
                GenericsType[] types = objectExpType.getGenericsTypes();
                if ("key".equals(propertyName)) {
                    if (types.length==2) {
                        ClassNode listKey = new ClassNode(List.class);
                        listKey.setGenericsTypes(new GenericsType[]{types[0]});
                        return listKey;
                    }
                } else if ("value".equals(propertyName)) {
                    if (types.length==2) {
                        ClassNode listValue = new ClassNode(List.class);
                        listValue.setGenericsTypes(new GenericsType[]{types[1]});
                        return listValue;
                    }
                } else {
                    addStaticTypeError("Spread operator on map only allows one of [key,value]", pexp);
                }
                return LIST_TYPE;
            } else if (objectExpType.isEnum()) {
                return objectExpType;
            } else {
                final AtomicReference<ClassNode> result = new AtomicReference<ClassNode>(ClassHelper.VOID_TYPE);
                existsProperty(pexp, false, new PropertyLookupVisitor(result));
                return result.get();
            }
        }
        if (exp instanceof ListExpression) {
            return inferListExpressionType((ListExpression)exp);
        } else if (exp instanceof MapExpression) {
            return inferMapExpressionType((MapExpression) exp);
        }
        if (exp instanceof MethodNode) {
            ClassNode ret = (ClassNode) exp.getNodeMetaData(StaticTypesMarker.INFERRED_RETURN_TYPE);
            return ret!=null?ret:((MethodNode)exp).getReturnType();
        }
        if (exp instanceof ClosureExpression) {
            ClassNode irt = (ClassNode) exp.getNodeMetaData(StaticTypesMarker.INFERRED_RETURN_TYPE);
            if (irt!=null) {
                irt = wrapTypeIfNecessary(irt);
                ClassNode result = CLOSURE_TYPE.getPlainNodeReference();
                result.setGenericsTypes(new GenericsType[]{new GenericsType(irt)});
                return result;
            }
        }
        if (exp instanceof RangeExpression) {
            ClassNode plain = ClassHelper.RANGE_TYPE.getPlainNodeReference();
            RangeExpression re = (RangeExpression) exp;
            ClassNode fromType = getType(re.getFrom());
            ClassNode toType = getType(re.getTo());
            if (fromType.equals(toType)) {
                plain.setGenericsTypes(new GenericsType[] {
                        new GenericsType(wrapTypeIfNecessary(fromType))
                });
            } else {
                plain.setGenericsTypes(new GenericsType[]{
                        new GenericsType(wrapTypeIfNecessary(lowestUpperBound(fromType, toType)))
                });
            }
            return plain;
        }
        return exp instanceof VariableExpression?((VariableExpression) exp).getOriginType():((Expression)exp).getType();
    }

    private ClassNode inferListExpressionType(final ListExpression list) {
        List<Expression> expressions = list.getExpressions();
        if (expressions.isEmpty()) {
            // cannot infer, return list type
            return list.getType();
        }
        ClassNode listType = list.getType();
        GenericsType[] genericsTypes = listType.getGenericsTypes();
        if ((genericsTypes == null
                || genericsTypes.length == 0
                || (genericsTypes.length == 1 && OBJECT_TYPE.equals(genericsTypes[0].getType())))
                && (!expressions.isEmpty())) {
            // maybe we can infer the component type
            List<ClassNode> nodes = new LinkedList<ClassNode>();
            for (Expression expression : expressions) {
                nodes.add(getType(expression));
            }
            ClassNode superType = getWrapper(lowestUpperBound(nodes)); // to be used in generics, type must be boxed
            ClassNode inferred = listType.getPlainNodeReference();
            inferred.setGenericsTypes(new GenericsType[]{new GenericsType(wrapTypeIfNecessary(superType))});
            return inferred;
        }
        return listType;
    }

    private ClassNode inferMapExpressionType(final MapExpression map) {
        ClassNode mapType = map.getType();
        List<MapEntryExpression> entryExpressions = map.getMapEntryExpressions();
        if (entryExpressions.isEmpty()) return mapType;
        GenericsType[] genericsTypes = mapType.getGenericsTypes();
        if (genericsTypes ==null
            || genericsTypes.length<2
            || (genericsTypes.length==2 && OBJECT_TYPE.equals(genericsTypes[0].getType()) && OBJECT_TYPE.equals(genericsTypes[1].getType()))) {
            List<ClassNode> keyTypes = new LinkedList<ClassNode>();
            List<ClassNode> valueTypes = new LinkedList<ClassNode>();
            for (MapEntryExpression entryExpression : entryExpressions) {
                keyTypes.add(getType(entryExpression.getKeyExpression()));
                valueTypes.add(getType(entryExpression.getValueExpression()));
            }
            ClassNode keyType = getWrapper(lowestUpperBound(keyTypes));  // to be used in generics, type must be boxed
            ClassNode valueType = getWrapper(lowestUpperBound(valueTypes));  // to be used in generics, type must be boxed
            if (!OBJECT_TYPE.equals(keyType) || !OBJECT_TYPE.equals(valueType)) {
                ClassNode inferred = mapType.getPlainNodeReference();
                inferred.setGenericsTypes(new GenericsType[]{new GenericsType(wrapTypeIfNecessary(keyType)), new GenericsType(wrapTypeIfNecessary(valueType))});
                return inferred;
            }
        }
        return mapType;
    }

    /**
     * If a method call returns a parameterized type, then we can perform additional inference on the
     * return type, so that the type gets actual type parameters. For example, the method
     * Arrays.asList(T...) is generified with type T which can be deduced from actual type
     * arguments.
     *
     * @param method the method node
     * @param arguments the method call arguments
     * @return parameterized, infered, class node
     */
    private ClassNode inferReturnTypeGenerics(final ClassNode receiver, final MethodNode method, final Expression arguments) {
        ClassNode returnType = method.getReturnType();
        GenericsType[] returnTypeGenerics = returnType.getGenericsTypes();
        List<GenericsType> placeholders = new LinkedList<GenericsType>();
        for (GenericsType returnTypeGeneric : returnTypeGenerics) {
            if (returnTypeGeneric.isPlaceholder() || returnTypeGeneric.isWildcard()) {
                placeholders.add(returnTypeGeneric);
            }
        }
        if (placeholders.isEmpty()) return returnType; // nothing to infer
        Map<String,GenericsType> resolvedPlaceholders = new HashMap<String, GenericsType>();
        GenericsUtils.extractPlaceholders(receiver, resolvedPlaceholders);
        GenericsUtils.extractPlaceholders(method.getReturnType(), resolvedPlaceholders);
        // then resolve receivers from method arguments
        Parameter[] parameters = method.getParameters();
        boolean isVargs = isVargs(parameters);
        ArgumentListExpression argList = InvocationWriter.makeArgumentList(arguments);
        List<Expression> expressions = argList.getExpressions();
        int paramLength = parameters.length;
        for (int i = 0; i < paramLength; i++) {
            boolean lastArg = i== paramLength -1;
            ClassNode type = parameters[i].getType();
            if (!type.isUsingGenerics() && type.isArray()) type=type.getComponentType();
            if (type.isUsingGenerics()) {
                ClassNode actualType = getType(expressions.get(i));
                if (isVargs && lastArg && actualType.isArray()) {
                    actualType=actualType.getComponentType();
                }
                actualType = wrapTypeIfNecessary(actualType);
                Map<String, GenericsType> typePlaceholders = GenericsUtils.extractPlaceholders(type.isArray()?type.getComponentType():type);
                if (OBJECT_TYPE.equals(type)) {
                    // special case for handing Object<E> -> Object
                    for (String key : typePlaceholders.keySet()) {
                        resolvedPlaceholders.put(key, new GenericsType(actualType));
                    }
                } else {
                    while (!actualType.equals(type)) {
                        Set<ClassNode> interfaces = actualType.getAllInterfaces();
                        boolean intf = false;
                        for (ClassNode anInterface : interfaces) {
                            if (anInterface.equals(type)) {
                                intf = true;
                                actualType = GenericsUtils.parameterizeInterfaceGenerics(actualType, anInterface);
                            }
                        }
                        if (!intf) actualType = actualType.getUnresolvedSuperClass();
                    }
                    Map<String, GenericsType> actualTypePlaceholders = GenericsUtils.extractPlaceholders(actualType);
                    for (Map.Entry<String, GenericsType> typeEntry : actualTypePlaceholders.entrySet()) {
                        String key = typeEntry.getKey();
                        GenericsType value = typeEntry.getValue();
                        GenericsType alias = typePlaceholders.get(key);
                        if (alias != null && alias.isPlaceholder()) {
                            resolvedPlaceholders.put(alias.getName(), value);
                        }
                    }
                }

            }
        }
        GenericsType[] copy = new GenericsType[returnTypeGenerics.length];
        for (int i = 0; i < copy.length; i++) {
            GenericsType returnTypeGeneric = returnTypeGenerics[i];
            if (returnTypeGeneric.isPlaceholder() || returnTypeGeneric.isWildcard()) {
                GenericsType resolved = resolvedPlaceholders.get(returnTypeGeneric.getName());
                if (resolved==null) resolved = returnTypeGeneric;
                copy[i] = resolved;
            } else {
                copy[i] = returnTypeGeneric;
            }
        }
        if (returnType.equals(OBJECT_TYPE)) {
            return copy[0].getType();
        }
        returnType = returnType.getPlainNodeReference();
        returnType.setGenericsTypes(copy);
        return returnType;
    }

    private void typeCheckMethodsWithGenerics(ClassNode receiver, ClassNode[] arguments, List<MethodNode> candidateMethods, Expression location) {
        if (!receiver.isUsingGenerics()) return;
        int failure=0;
        GenericsType[] methodGenericTypes = null;
        for (MethodNode method : candidateMethods) {
            ClassNode methodNodeReceiver = method.getDeclaringClass();
            if (!implementsInterfaceOrIsSubclassOf(receiver, methodNodeReceiver) || !methodNodeReceiver.isUsingGenerics()) continue;
            // both candidate method and receiver have generic information so a check is possible
            Parameter[] parameters = method.getParameters();
            int argNum = 0;
            for (Parameter parameter : parameters) {
                ClassNode type = parameter.getType();
                if (type.isUsingGenerics()) {
                    methodGenericTypes =
                            GenericsUtils.alignGenericTypes(
                                    receiver.redirect().getGenericsTypes(),
                                    receiver.getGenericsTypes(),
                                    type.getGenericsTypes());
                    if (methodGenericTypes.length==1) {
                        ClassNode nodeType = getWrapper(methodGenericTypes[0].getType());
                        ClassNode actualType = getWrapper(arguments[argNum]);
                        if (!actualType.isDerivedFrom(nodeType)) {
                            failure++;
                        }
                    } else {
                        // not sure this is possible !
                    }
                } else if (type.isArray() && type.getComponentType().isUsingGenerics()) {
                    ClassNode componentType = type.getComponentType();
                    methodGenericTypes =
                            GenericsUtils.alignGenericTypes(
                                    receiver.redirect().getGenericsTypes(),
                                    receiver.getGenericsTypes(),
                                    componentType.getGenericsTypes());
                    if (methodGenericTypes.length==1) {
                        ClassNode nodeType = getWrapper(methodGenericTypes[0].getType());
                        ClassNode actualType = getWrapper(arguments[argNum].getComponentType());
                        if (!actualType.equals(nodeType)) {
                            failure++;
                            // for proper error message
                            methodGenericTypes[0].setType(methodGenericTypes[0].getType().makeArray());
                        }
                    } else {
                        // not sure this is possible !
                    }
                }
                argNum++;
            }
        }
        if (failure==candidateMethods.size()) {
            if (failure==1) {
                MethodNode method = candidateMethods.get(0);
                ClassNode[] parameterTypes = new ClassNode[methodGenericTypes.length];
                for (int i = 0; i < methodGenericTypes.length; i++) {
                    parameterTypes[i] = methodGenericTypes[i].getType();
                }
                addStaticTypeError("Cannot call " + receiver.getName()+"#"+
                        toMethodParametersString(method.getName(), parameterTypes) +
                        " with arguments " + Arrays.asList(arguments), location);
            } else {
                addStaticTypeError("No matching method found for arguments "+Arrays.asList(arguments), location);
            }
        }
    }

    protected void addStaticTypeError(final String msg, final ASTNode expr) {
        if (expr.getColumnNumber() > 0 && expr.getLineNumber() > 0) {
            addError(StaticTypesTransformation.STATIC_ERROR_PREFIX + msg, expr);
        } else {
            // ignore errors which are related to unknown source locations
            // because they are likely related to generated code
        }
    }

    public void setMethodsToBeVisited(final Set<MethodNode> methodsToBeVisited) {
        this.methodsToBeVisited = methodsToBeVisited;
    }

	public void performSecondPass() {
		for (Expression expression : secondPassExpressions) {
			if (expression instanceof MethodCallExpression) {
				MethodCallExpression call = (MethodCallExpression) expression;
				Expression objectExpression = call.getObjectExpression();
			 	if (objectExpression instanceof VariableExpression) {
					 // this should always be the case, but adding a test is safer
					 Variable target = findTargetVariable((VariableExpression) objectExpression);
					 if (target instanceof VariableExpression) {
						 VariableExpression var = (VariableExpression) target;
						 List<ClassNode> classNodes = closureSharedVariablesAssignmentTypes.get(var);
						 if (classNodes!=null && classNodes.size()>1) {
							 ClassNode lub = lowestUpperBound(classNodes);
							 MethodNode methodNode = (MethodNode) call.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET);
							 // we must check that such a method exists on the LUB
							 Parameter[] parameters = methodNode.getParameters();
							 ClassNode[] params = new ClassNode[parameters.length];
							 for (int i = 0; i < params.length; i++) {
								 params[i] = parameters[i].getType();								 
							 }
							 List<MethodNode> method = findMethod(lub, methodNode.getName(), params);
							 if (method.size()!=1) {
								 addStaticTypeError("A closure shared variable ["+target.getName()+"] has been assigned with various types and the method" +
								" ["+toMethodParametersString(methodNode.getName(), params)+"]"+
								 " does not exist in the lowest upper bound of those types: ["+
								 lub.toString(false)+"]. In general, this is a bad practice (variable reuse) because the compiler cannot"+
								 " determine safely what is the type of the variable at the moment of the call in a multithreaded context.", call);
							 }
						 }
					 }
				 }
			}
		}
	}

    /**
     * Returns a wrapped type if, and only if, the provided class node is a primitive type.
     * This method differs from {@link ClassHelper#getWrapper(org.codehaus.groovy.ast.ClassNode)} as it will
     * return the same instance if the provided type is not a generic type.
     * @param type
     * @return
     */
    private static ClassNode wrapTypeIfNecessary(ClassNode type) {
        if (isPrimitiveType(type)) return getWrapper(type);
        return type;
    }

	/**
     * A visitor used as a callback to {@link StaticTypeCheckingVisitor#existsProperty(org.codehaus.groovy.ast.expr.PropertyExpression, boolean, org.codehaus.groovy.ast.ClassCodeVisitorSupport)}
     * which will return set the type of the found property in the provided reference.
     */
    private static class PropertyLookupVisitor extends ClassCodeVisitorSupport {
        private final AtomicReference<ClassNode> result;

        public PropertyLookupVisitor(final AtomicReference<ClassNode> result) {
            this.result = result;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return null;
        }

        @Override
        public void visitMethod(final MethodNode node) {
            result.set(node.getReturnType());
        }

        @Override
        public void visitProperty(final PropertyNode node) {
            result.set(node.getType());
        }

        @Override
        public void visitField(final FieldNode field) {
            result.set(field.getType());
        }
    }
}
