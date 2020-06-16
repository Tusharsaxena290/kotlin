/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.atomicfu.compiler.extensions

import org.jetbrains.kotlin.backend.common.deepCopyWithVariables
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.*
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder.buildValueParameter
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder.buildFunction
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder.buildBlockBody
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.*

private const val KOTLIN = "kotlin"
private const val AFU_PKG = "kotlinx/atomicfu"
private const val LOCKS = "locks"
private const val ATOMIC_CONSTRUCTOR = "atomic"
private const val ATOMICFU_VALUE_TYPE = """Atomic(Int|Long|Boolean|Ref)"""
private const val ATOMIC_ARRAY_TYPE = """Atomic(Int|Long|Boolean|)Array"""
private const val ATOMIC_ARRAY_FACTORY_FUNCTION = "atomicArrayOfNulls"
private const val ATOMICFU_RUNTIME_FUNCTION_PREDICATE = "atomicfu_"
private const val REENTRANT_LOCK_TYPE = "ReentrantLock"
private const val GETTER = "atomicfu\$getter"
private const val SETTER = "atomicfu\$setter"
private const val GET = "get"
private const val SET = "set"

private fun String.prettyStr() = replace('/', '.')

class AtomicFUTransformer(override val context: IrPluginContext) : IrElementTransformerVoid(), TransformerHelpers {

    private val irBuiltIns = context.irBuiltIns

    private val AFU_CLASSES: Map<String, IrType> = mapOf(
        "AtomicInt" to irBuiltIns.intType,
        "AtomicLong" to irBuiltIns.longType,
        "AtomicRef" to irBuiltIns.anyType,
        "AtomicBoolean" to irBuiltIns.booleanType
    )

    private val AFU_ARRAY_CLASSES: Map<String, String> = mapOf(
        "AtomicIntArray" to "IntArray",
        "AtomicLongArray" to "LongArray",
        "AtomicBooleanArray" to "BooleanArray",
        "AtomicArray" to "Array"
    )

    override fun visitFile(irFile: IrFile): IrFile {
        irFile.declarations.forEachIndexed { index, irDeclaration ->
            irFile.declarations[index] = irDeclaration.transformAtomicInlineDeclaration()
        }
        return super.visitFile(irFile)
    }

    override fun visitClass(irClass: IrClass): IrStatement {
        irClass.declarations.forEachIndexed { index, irDeclaration ->
            irClass.declarations[index] = irDeclaration.transformAtomicInlineDeclaration()
        }
        return super.visitClass(irClass)
    }

    override fun visitProperty(property: IrProperty): IrStatement {
        if (property.backingField != null) {
            val backingField = property.backingField!!
            if (backingField.initializer != null) {
                val initializer = backingField.initializer!!.expression.transformAtomicValueInitializer(backingField)
                property.backingField!!.initializer = IrExpressionBodyImpl(initializer)
            }
        }
        return super.visitProperty(property)
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        transformDeclarationBody(declaration.body, declaration)
        return super.visitFunction(declaration)
    }

    override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer): IrStatement {
        transformDeclarationBody(declaration.body, declaration)
        return super.visitAnonymousInitializer(declaration)
    }

    private fun transformDeclarationBody(body: IrBody?, parent: IrDeclaration) {
        if (body is IrBlockBody) {
            body.statements.forEachIndexed { i, stmt ->
                body.statements[i] = stmt.transformStatement(parent)
            }
        }
    }

    private fun IrExpression.transformAtomicValueInitializer(parentDeclaration: IrDeclaration) =
        when {
            type.isAtomicValueType() -> getPureTypeValue().transformAtomicFunctionCall(parentDeclaration)
            type.isAtomicArrayType() -> buildPureTypeArrayConstructor()
            type.isReentrantLockType() -> buildConstNull()
            else -> this
        }

    private fun IrDeclaration.transformAtomicInlineDeclaration(): IrDeclaration {
        if (this is IrFunction &&
            isInline &&
            extensionReceiverParameter != null &&
            extensionReceiverParameter!!.type.isAtomicValueType()
        ) {
            val type = extensionReceiverParameter!!.type
            val valueType = type.atomicToValueType()
            val getterType = buildGetterType(valueType)
            val setterType = buildSetterType(valueType)
            val valueParametersCount = valueParameters.size
            val extendedValueParameters = valueParameters + listOf(
                buildValueParameter(Name.identifier(GETTER), valueParametersCount, getterType, IrDeclarationOrigin.DEFINED),
                buildValueParameter(Name.identifier(SETTER), valueParametersCount + 1, setterType, IrDeclarationOrigin.DEFINED)
            )
            val oldDeclaration = this
            return buildFunction(
                name = name,
                returnType = returnType,
                parent = parent,
                visibility = visibility,
                isInline = isInline,
                origin = origin
            ).apply {
                oldDeclaration.body?.acceptVoid(ChangeDeclarationParentsVisitor(oldDeclaration,this))
                body = oldDeclaration.body
                extendedValueParameters.forEach { it.parent = this }
                valueParameters = extendedValueParameters
                extensionReceiverParameter = null
            }
        }
        return this
    }

    private class ChangeDeclarationParentsVisitor(val parent: IrDeclarationParent, val newParent: IrDeclarationParent) : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitDeclaration(declaration: IrDeclaration) {
            if (declaration.parent == parent) declaration.parent = newParent
            super.visitDeclaration(declaration)
        }
    }

    private fun IrExpression.getPureTypeValue(): IrExpression {
        require(this is IrCall && isAtomicFactoryFunction()) { "Illegal initializer for the atomic property $this" }
        return getValueArgument(0)!!
    }

    private fun IrExpression.buildPureTypeArrayConstructor() =
        when (this) {
            is IrConstructorCall -> {
                require(isAtomicArrayConstructor())
                val arrayConstructorSymbol = type.getArrayConstructorSymbol { it.owner.valueParameters.size == 1 }
                val size = getValueArgument(0)
                IrConstructorCallImpl(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    irBuiltIns.unitType, arrayConstructorSymbol,
                    0, 0, 1
                ).apply {
                    putValueArgument(0, size)
                }
            }
            is IrCall -> {
                require(isAtomicArrayFactoryFunction()) { "Unsupported atomic array factory function $this" }
                val arrayFactorySymbol = referencePackageFunction("kotlin", "arrayOfNulls")
                val arrayElementType = getTypeArgument(0)!!
                val size = getValueArgument(0)
                buildCall(
                    target = arrayFactorySymbol,
                    type = type,
                    typeArguments = listOf(arrayElementType),
                    valueArguments = listOf(size)
                )
            }
            else -> error("Illegal type of atomic array initializer")
        }

    private fun IrCall.runtimeInlineAtomicFunctionCall(callReceiver: IrExpression, accessors: List<IrExpression>): IrCall {
        val valueArguments = getValueArguments()
        val functionName = getAtomicFunctionName()
        val receiverType = callReceiver.type.atomicToValueType()
        val runtimeFunction = getRuntimeFunctionSymbol(functionName, receiverType)
        return buildCall(
            target = runtimeFunction,
            type = type,
            origin = IrStatementOrigin.INVOKE,
            typeArguments = if (runtimeFunction.owner.typeParameters.size == 1) listOf(receiverType) else emptyList(),
            valueArguments = valueArguments + accessors
        )
    }

    private fun IrStatement.transformStatement(parentDeclaration: IrDeclaration) =
        when (this) {
            is IrExpression -> transformAtomicFunctionCall(parentDeclaration)
            is IrVariable -> {
                apply { initializer = initializer?.transformAtomicFunctionCall(parentDeclaration) }
            }
            else -> this
        }

    private fun IrExpression.transformAtomicFunctionCall(parentDeclaration: IrDeclaration): IrExpression {
        // erase unchecked cast to the Atomic* type
        if (this is IrTypeOperatorCall && operator == IrTypeOperator.CAST && typeOperand.isAtomicValueType()) {
            return argument
        }
        if (isAtomicValueInitializerCall()) {
            return transformAtomicValueInitializer(parentDeclaration)
        }
        when (this) {
            is IrTypeOperatorCall -> {
                return apply { argument = argument.transformAtomicFunctionCall(parentDeclaration) }
            }
            is IrReturn -> {
                return apply { value = value.transformAtomicFunctionCall(parentDeclaration) }
            }
            is IrSetVariable -> {
                return apply { value = value.transformAtomicFunctionCall(parentDeclaration) }
            }
            is IrSetField -> {
                return apply { value = value.transformAtomicFunctionCall(parentDeclaration) }
            }
            is IrIfThenElseImpl -> {
                return apply {
                    branches.forEachIndexed { i, branch ->
                        branches[i] = branch.apply {
                            condition = condition.transformAtomicFunctionCall(parentDeclaration)
                            result = result.transformAtomicFunctionCall(parentDeclaration)
                        }
                    }
                }
            }
            is IrTry -> {
                return apply {
                    tryResult = tryResult.transformAtomicFunctionCall(parentDeclaration)
                    catches.forEach {
                        it.result = it.result.transformAtomicFunctionCall(parentDeclaration)
                    }
                    finallyExpression = finallyExpression?.transformAtomicFunctionCall(parentDeclaration)
                }
            }
            is IrBlock -> {
                return apply {
                    statements.forEachIndexed { i, stmt ->
                        statements[i] = stmt.transformStatement(parentDeclaration)
                    }
                }
            }
            is IrCall -> {
                if (dispatchReceiver != null) {
                    dispatchReceiver = dispatchReceiver!!.transformAtomicFunctionCall(parentDeclaration)
                }
                getValueArguments().forEachIndexed { i, arg ->
                    putValueArgument(i, arg?.transformAtomicFunctionCall(parentDeclaration) as IrExpression)
                }
                val isInline = symbol.owner.isInline
                val callReceiver = extensionReceiver ?: dispatchReceiver ?: return this
                if (symbol.isKotlinxAtomicfuPackage() && callReceiver.type.isAtomicValueType()) {
                    // 1. transform function call on the atomic class field
                    if (callReceiver is IrCall) {
                        val accessors = callReceiver.getPropertyAccessors(parentDeclaration)
                        return runtimeInlineAtomicFunctionCall(callReceiver, accessors)
                    }
                    // 2. transform function call on the atomic extension receiver
                    if (callReceiver is IrGetValue) {
                        val containingDeclaration = callReceiver.symbol.owner.parent as IrFunction
                        val transformedTarget = containingDeclaration.getDeclarationWithAccessorParameters()
                        val accessorParameters = transformedTarget.valueParameters.takeLast(2).map { it.capture() }
                        return runtimeInlineAtomicFunctionCall(callReceiver, accessorParameters)
                    }
                }
                // 3. transform inline Atomic* extension function call
                if (isInline && callReceiver is IrCall && callReceiver.type.isAtomicValueType()) {
                    val accessors = callReceiver.getPropertyAccessors(parentDeclaration)
                    val dispatch = dispatchReceiver
                    val args = getValueArguments()
                    val transformedTarget = symbol.owner.getDeclarationWithAccessorParameters()
                    return buildCall(
                        target = transformedTarget.symbol,
                        type = type,
                        origin = IrStatementOrigin.INVOKE,
                        valueArguments = args + accessors
                    ).apply {
                        dispatchReceiver = dispatch
                    }
                }
            }
        }
        return this
    }

    private fun IrFunction.getDeclarationWithAccessorParameters(): IrFunction {
        val parent = parent as IrDeclarationContainer
        val params = valueParameters.map { it.type }
        val extensionType = extensionReceiverParameter?.type?.atomicToValueType()
        return try {
            parent.declarations.single {
                it is IrFunction &&
                        it.name == symbol.owner.name &&
                        it.valueParameters.size == params.size + 2 &&
                        it.valueParameters.dropLast(2).withIndex().all { p -> p.value.type == params[p.index] } &&
                        it.getGetterReturnType() == extensionType
            } as IrFunction
        } catch (e: RuntimeException) {
            error("Multiple transformed declarations with accessor parameters matching the signature")
        }
    }

    private fun IrExpression.isAtomicValueInitializerCall() =
        (this is IrCall && (this.isAtomicFactoryFunction() || this.isAtomicArrayFactoryFunction())) ||
                (this is IrConstructorCall && this.isAtomicArrayConstructor()) ||
                type.isReentrantLockType()

    private fun IrCall.isArrayElementGetter() =
        dispatchReceiver != null &&
                dispatchReceiver!!.type.isAtomicArrayType() &&
                symbol.owner.name.asString() == GET

    private fun IrCall.buildGetterLambda(parentDeclaration: IrDeclaration): IrExpression {
        val isArrayElement = isArrayElementGetter()
        val getterCall = if (isArrayElement) dispatchReceiver as IrCall else this
        val valueType = type.atomicToValueType()
        val getField = buildGetField(getterCall.getBackingField(), getterCall.dispatchReceiver)
        val getterType = buildGetterType(valueType)
        val getterBody = if (isArrayElement) {
            val getSymbol = referenceFunction(getterCall.type.referenceClass(), GET)
            val elementIndex = getValueArgument(0)!!.deepCopyWithVariables()
            buildCall(
                target = getSymbol,
                type = valueType,
                origin = IrStatementOrigin.LAMBDA,
                valueArguments = listOf(elementIndex)
            ).apply {
                dispatchReceiver = getField.deepCopyWithVariables()
            }
        } else {
            getField.deepCopyWithVariables()
        }
        return buildAccessorLambda(
            name = getterName(getterCall.symbol.owner.name.getFieldName()),
            type = getterType,
            returnType = valueType,
            valueParameters = emptyList(),
            body = getterBody,
            parentDeclaration = parentDeclaration
        )
    }

    private fun IrCall.buildSetterLambda(parentDeclaration: IrDeclaration): IrExpression {
        val isArrayElement = isArrayElementGetter()
        val getterCall = if (isArrayElement) dispatchReceiver as IrCall else this
        val valueType = type.atomicToValueType()
        val valueParameter = buildValueParameter(index = 0, type = valueType)
        val setterType = buildSetterType(valueType)
        val setterBody = if (isArrayElement) {
            val setSymbol = referenceFunction(getterCall.type.referenceClass(), SET)
            val elementIndex = getValueArgument(0)!!.deepCopyWithVariables()
            buildCall(
                target = setSymbol,
                type = context.irBuiltIns.unitType,
                origin = IrStatementOrigin.LAMBDA,
                valueArguments = listOf(elementIndex, valueParameter.capture())
            ).apply {
                dispatchReceiver = getterCall
            }
        } else {
            buildSetField(getterCall.getBackingField(), getterCall.dispatchReceiver, valueParameter.capture())
        }
        return buildAccessorLambda(
            name = setterName(getterCall.symbol.owner.name.getFieldName()),
            type = setterType,
            returnType = context.irBuiltIns.unitType,
            valueParameters = listOf(valueParameter),
            body = setterBody,
            parentDeclaration = parentDeclaration
        )
    }

    private fun IrCall.getBackingField(): IrField {
        val correspondingPropertySymbol = (symbol.owner as IrSimpleFunction).correspondingPropertySymbol!!
        return correspondingPropertySymbol.owner.backingField!!
    }

    private fun buildAccessorLambda(
        name: String,
        type: IrType,
        returnType: IrType,
        valueParameters: List<IrValueParameter>,
        body: IrExpression,
        parentDeclaration: IrDeclaration
    ): IrFunctionExpression {
        val accessorFunction = buildFunction(
            name = name,
            returnType = returnType,
            parent = parentDeclaration as IrDeclarationParent,
            origin = IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR,
            isInline = true
        ).apply {
            valueParameters.forEach { it.parent = this }
            this.valueParameters = valueParameters
            this.body = buildBlockBody(listOf(body))
            origin = IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
        }
        return IrFunctionExpressionImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
            type,
            accessorFunction,
            IrStatementOrigin.LAMBDA
        )
    }

    private fun buildSetField(backingField: IrField, ownerClass: IrExpression?, value: IrGetValue): IrSetField {
        val receiver = if (ownerClass is IrTypeOperatorCall) ownerClass.argument as IrGetValue else ownerClass
        val fieldSymbol = backingField.symbol
        return buildSetField(
            symbol = fieldSymbol,
            receiver = receiver,
            value = value
        )
    }

    private fun buildGetField(backingField: IrField, ownerClass: IrExpression?): IrGetField {
        val receiver = if (ownerClass is IrTypeOperatorCall) ownerClass.argument as IrGetValue else ownerClass
        return buildGetField(backingField.symbol, receiver)
    }

    private fun IrCall.getPropertyAccessors(parentDeclaration: IrDeclaration): List<IrExpression> =
        listOf(buildGetterLambda(parentDeclaration), buildSetterLambda(parentDeclaration))

    private fun getRuntimeFunctionSymbol(name: String, type: IrType): IrSimpleFunctionSymbol {
        val functionName = when (name) {
            "value.<get-value>" -> "getValue"
            "value.<set-value>" -> "setValue"
            else -> name
        }
        return referencePackageFunction("kotlinx.atomicfu", "$ATOMICFU_RUNTIME_FUNCTION_PREDICATE$functionName") {
            val typeArg = it.owner.getGetterReturnType()
            !(typeArg as IrType).isPrimitiveType() || typeArg == type
        }
    }

    private fun IrFunction.getGetterReturnType() = (valueParameters[valueParameters.lastIndex - 1].type as IrSimpleType).arguments.first()

    private fun IrCall.isAtomicFactoryFunction(): Boolean {
        val name = symbol.owner.name
        return !name.isSpecial && name.identifier == ATOMIC_CONSTRUCTOR
    }

    private fun IrCall.isAtomicArrayFactoryFunction(): Boolean {
        val name = symbol.owner.name
        return !name.isSpecial && name.identifier == ATOMIC_ARRAY_FACTORY_FUNCTION
    }

    private fun IrConstructorCall.isAtomicArrayConstructor(): Boolean {
        val name = (type as IrSimpleType).classifier.descriptor.name
        return !name.isSpecial && name.identifier.matches(Regex(ATOMIC_ARRAY_TYPE))
    }

    private fun IrSymbol.isKotlinxAtomicfuPackage() =
        this.isPublicApi && signature.packageFqName().asString() == AFU_PKG.prettyStr()

    private fun IrType.isAtomicValueType() = belongsTo(ATOMICFU_VALUE_TYPE)
    private fun IrType.isAtomicArrayType() = belongsTo(ATOMIC_ARRAY_TYPE)
    private fun IrType.isReentrantLockType() = belongsTo("$AFU_PKG/$LOCKS", REENTRANT_LOCK_TYPE)

    private fun IrType.belongsTo(typeName: String) = belongsTo(AFU_PKG, typeName)

    private fun IrType.belongsTo(packageName: String, typeName: String): Boolean {
        if (this !is IrSimpleType || !(classifier.isPublicApi && classifier is IrClassSymbol)) return false
        val signature = classifier.signature.asPublic()!!
        val pckg = signature.packageFqName().asString()
        val type = signature.declarationFqName
        return pckg == packageName.prettyStr() && type.matches(typeName.toRegex())
    }

    private fun IrCall.getAtomicFunctionName(): String {
        val signature = symbol.signature
        val classFqName = if (signature is IdSignature.AccessorSignature) {
            signature.accessorSignature.declarationFqName
        } else (signature.asPublic()!!).declarationFqName
        val pattern = "$ATOMICFU_VALUE_TYPE\\.(.*)".toRegex()
        return pattern.findAll(classFqName).firstOrNull()?.let { it.groupValues[2] } ?: classFqName
    }

    private fun IrType.atomicToValueType(): IrType {
        require(isAtomicValueType())
        val classId = ((this as IrSimpleType).classifier.signature as IdSignature.PublicSignature).declarationFqName
        if (classId == "AtomicRef") {
            return arguments.first().typeOrNull ?: error("$AFU_PKG/AtomicRef type parameter is not IrTypeProjection")
        }
        return AFU_CLASSES[classId] ?: error("IrType ${this.getClass()} does not match any of atomicfu types")
    }

    private fun buildConstNull() = IrConstImpl.constNull(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.irBuiltIns.anyType)

    private fun IrType.getArrayConstructorSymbol(predicate: (IrConstructorSymbol) -> Boolean = { true }): IrConstructorSymbol {
        val afuClassId = ((this as IrSimpleType).classifier.signature as IdSignature.PublicSignature).declarationFqName
        val classId = FqName("$KOTLIN.${AFU_ARRAY_CLASSES[afuClassId]!!}")
        return context.referenceConstructors(classId).single(predicate)
    }

    private fun IrType.referenceClass(): IrClassSymbol {
        val afuClassId = ((this as IrSimpleType).classifier.signature as IdSignature.PublicSignature).declarationFqName
        val classId = FqName("$KOTLIN.${AFU_ARRAY_CLASSES[afuClassId]!!}")
        return context.referenceClass(classId)!!
    }

    private fun referencePackageFunction(
        packageName: String,
        name: String,
        predicate: (IrFunctionSymbol) -> Boolean = { true }
    ) = context.referenceFunctions(FqName("$packageName.$name")).single(predicate)

    private fun referenceFunction(classSymbol: IrClassSymbol, functionName: String): IrFunctionSymbol {
        val functionId = FqName("$KOTLIN.${classSymbol.owner.name}.$functionName")
        return context.referenceFunctions(functionId).single()
    }

    companion object {
        fun transform(irFile: IrFile, context: IrPluginContext) =
            irFile.transform(AtomicFUTransformer(context), null)
    }
}
