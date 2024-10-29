package org.usvm.machine.state.concreteMemory

import io.ksmt.expr.KBitVec16Value
import io.ksmt.expr.KBitVec32Value
import io.ksmt.expr.KBitVec64Value
import io.ksmt.expr.KBitVec8Value
import io.ksmt.expr.KFp32Value
import io.ksmt.expr.KFp64Value
import io.ksmt.utils.asExpr
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.runBlocking
import org.jacodb.api.jvm.JcArrayType
import org.jacodb.api.jvm.JcByteCodeLocation
import org.jacodb.api.jvm.JcClassOrInterface
import org.jacodb.api.jvm.JcClassType
import org.jacodb.api.jvm.JcField
import org.jacodb.api.jvm.JcMethod
import org.jacodb.api.jvm.JcPrimitiveType
import org.jacodb.api.jvm.JcRefType
import org.jacodb.api.jvm.JcType
import org.jacodb.api.jvm.JcTypeVariable
import org.jacodb.api.jvm.JcTypedField
import org.jacodb.api.jvm.RegisteredLocation
import org.jacodb.api.jvm.ext.annotation
import org.jacodb.api.jvm.ext.boolean
import org.jacodb.api.jvm.ext.byte
import org.jacodb.api.jvm.ext.char
import org.jacodb.api.jvm.ext.double
import org.jacodb.api.jvm.ext.findClassOrNull
import org.jacodb.api.jvm.ext.findFieldOrNull
import org.jacodb.api.jvm.ext.findTypeOrNull
import org.jacodb.api.jvm.ext.float
import org.jacodb.api.jvm.ext.humanReadableSignature
import org.jacodb.api.jvm.ext.int
import org.jacodb.api.jvm.ext.isAssignable
import org.jacodb.api.jvm.ext.isEnum
import org.jacodb.api.jvm.ext.long
import org.jacodb.api.jvm.ext.objectType
import org.jacodb.api.jvm.ext.packageName
import org.jacodb.api.jvm.ext.short
import org.jacodb.api.jvm.ext.superClasses
import org.jacodb.api.jvm.ext.toType
import org.jacodb.api.jvm.ext.void
import org.jacodb.approximation.Approximations
import org.jacodb.approximation.JcEnrichedVirtualField
import org.jacodb.approximation.JcEnrichedVirtualMethod
import org.jacodb.approximation.OriginalClassName
import org.jacodb.impl.features.classpaths.JcUnknownType
import org.jacodb.impl.features.hierarchyExt
import org.jacodb.impl.fs.LazyClassSourceImpl
import org.usvm.NULL_ADDRESS
import org.usvm.UBoolExpr
import org.usvm.UBoolSort
import org.usvm.UConcreteHeapAddress
import org.usvm.UConcreteHeapRef
import org.usvm.UExpr
import org.usvm.UHeapRef
import org.usvm.UIndexedMocker
import org.usvm.UIteExpr
import org.usvm.UNullRef
import org.usvm.USort
import org.usvm.USymbol
import org.usvm.api.SymbolicIdentityMap
import org.usvm.api.SymbolicList
import org.usvm.api.SymbolicMap
import org.usvm.api.encoder.EncoderFor
import org.usvm.api.encoder.ObjectEncoder
import org.usvm.api.readArrayIndex
import org.usvm.api.readField
import org.usvm.api.util.JcConcreteMemoryClassLoader
import org.usvm.api.util.JcTestInterpreterDecoderApi
import org.usvm.api.util.JcTestStateResolver
import org.usvm.api.util.JcTestStateResolver.ResolveMode
import org.usvm.api.util.Reflection.allocateInstance
import org.usvm.api.util.Reflection.getFieldValue
import org.usvm.api.util.Reflection.invoke
import org.usvm.api.util.Reflection.toJavaClass
import org.usvm.api.util.Reflection.toJavaExecutable
import org.usvm.instrumentation.util.getFieldValue as getFieldValueUnsafe
import org.usvm.instrumentation.util.setFieldValue as setFieldValueUnsafe
import org.usvm.collection.array.UArrayIndexLValue
import org.usvm.collection.array.UArrayRegion
import org.usvm.collection.array.UArrayRegionId
import org.usvm.collection.array.length.UArrayLengthLValue
import org.usvm.collection.array.length.UArrayLengthsRegion
import org.usvm.collection.array.length.UArrayLengthsRegionId
import org.usvm.collection.field.UFieldLValue
import org.usvm.collection.field.UFieldsRegion
import org.usvm.collection.field.UFieldsRegionId
import org.usvm.collection.map.length.UMapLengthLValue
import org.usvm.collection.map.length.UMapLengthRegion
import org.usvm.collection.map.length.UMapLengthRegionId
import org.usvm.collection.map.primitive.UMapRegionId
import org.usvm.collection.map.ref.URefMapEntryLValue
import org.usvm.collection.map.ref.URefMapRegion
import org.usvm.collection.map.ref.URefMapRegionId
import org.usvm.collection.set.primitive.USetRegionId
import org.usvm.collection.set.ref.UAllocatedRefSetWithInputElements
import org.usvm.collection.set.ref.UInputRefSetWithInputElements
import org.usvm.collection.set.ref.URefSetEntries
import org.usvm.collection.set.ref.URefSetEntryLValue
import org.usvm.collection.set.ref.URefSetRegion
import org.usvm.collection.set.ref.URefSetRegionId
import org.usvm.constraints.UTypeConstraints
import org.usvm.instrumentation.util.isSameSignatures
import org.usvm.instrumentation.util.isStatic
import org.usvm.isFalse
import org.usvm.isTrue
import org.usvm.machine.JcConcreteInvocationResult
import org.usvm.machine.JcContext
import org.usvm.machine.JcMethodCall
import org.usvm.machine.USizeSort
import org.usvm.machine.interpreter.JcExprResolver
import org.usvm.machine.interpreter.JcLambdaCallSite
import org.usvm.machine.interpreter.JcLambdaCallSiteMemoryRegion
import org.usvm.machine.interpreter.JcLambdaCallSiteRegionId
import org.usvm.machine.interpreter.statics.JcStaticFieldLValue
import org.usvm.machine.interpreter.statics.JcStaticFieldRegionId
import org.usvm.machine.interpreter.statics.JcStaticFieldsMemoryRegion
import org.usvm.machine.interpreter.statics.staticFieldsInitializedFlagField
import org.usvm.machine.state.JcState
import org.usvm.machine.state.newStmt
import org.usvm.machine.state.skipMethodInvocationWithValue
import org.usvm.machine.state.throwExceptionWithoutStackFrameDrop
import org.usvm.memory.UMemory
import org.usvm.memory.UMemoryRegion
import org.usvm.memory.UMemoryRegionId
import org.usvm.memory.URegistersStack
import org.usvm.mkSizeExpr
import org.usvm.sizeSort
import org.usvm.util.Maybe
import org.usvm.util.jcTypeOf
import org.usvm.util.name
import org.usvm.util.typedField
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

//region Physical Address

private data class PhysicalAddress(
    val obj: Any?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PhysicalAddress

        return obj === other.obj
    }

    override fun hashCode(): Int {
        return System.identityHashCode(obj)
    }

    val isNull: Boolean by lazy {
        obj == null
    }
}

//endregion

//region Child Kind

private interface ChildKind

private data class FieldChildKind(
    val field: Field
) : ChildKind

private data class ArrayIndexChildKind(
    val index: Int,
) : ChildKind

//endregion

//region Cell

private data class Cell(
    val address: PhysicalAddress?
) {
    val isConcrete = address != null
    val isSymbolic = address == null

    companion object {
        operator fun invoke(): Cell {
            return Cell(null)
        }
    }
}

//endregion

//region Helpers And Extensions

@Suppress("RecursivePropertyAccessor")
private val JcClassType.allFields: List<JcTypedField>
    get() = declaredFields + (superType?.allFields ?: emptyList())

@Suppress("RecursivePropertyAccessor")
private val Class<*>.allFields: Array<Field>
    get() = declaredFields + (superclass?.allFields ?: emptyArray())

val JcClassType.allInstanceFields: List<JcTypedField>
    get() = allFields.filter { !it.isStatic }

private val JcClassType.declaredInstanceFields: List<JcTypedField>
    get() = declaredFields.filter { !it.isStatic }

private val Class<*>.allInstanceFields: List<Field>
    get() = allFields.filter { !Modifier.isStatic(it.modifiers) }

private val JcClassOrInterface.staticFields: List<JcField>
    get() = declaredFields.filter { it.isStatic }

fun Field.getFieldValue(obj: Any): Any? {
    check(!isStatic)
    isAccessible = true
    return get(obj)
}

fun Field.getStaticFieldValue(): Any? {
    check(isStatic)
    isAccessible = true
    return get(null)
//     TODO: null!! #CM #Valya
//    return getFieldValueUnsafe(null)
}

fun Field.setStaticFieldValue(value: Any?) {
//    isAccessible = true
//    set(null, value)
    setFieldValueUnsafe(null, value)
}

private val Field.isFinal: Boolean
    get() = Modifier.isFinal(modifiers)

fun JcField.getFieldValue(obj: Any): Any? {
    if (this is JcEnrichedVirtualField) {
        val javaField = obj.javaClass.allInstanceFields.find { it.name == name }!!
        return javaField.getFieldValue(obj)
    }

    return this.getFieldValue(JcConcreteMemoryClassLoader, obj)
}

fun Field.setFieldValue(obj: Any, value: Any?) {
    isAccessible = true
    set(obj, value)
}

@Suppress("UNCHECKED_CAST")
private fun <Value> Any.getArrayValue(index: Int): Value {
    return when (this) {
        is IntArray -> this[index] as Value
        is ByteArray -> this[index] as Value
        is CharArray -> this[index] as Value
        is LongArray -> this[index] as Value
        is FloatArray -> this[index] as Value
        is ShortArray -> this[index] as Value
        is DoubleArray -> this[index] as Value
        is BooleanArray -> this[index] as Value
        is Array<*> -> this[index] as Value
        else -> error("getArrayValue: unexpected array $this")
    }
}

@Suppress("UNCHECKED_CAST")
private fun <Value> Any.setArrayValue(index: Int, value: Value) {
    when (this) {
        is IntArray -> this[index] = value as Int
        is ByteArray -> this[index] = value as Byte
        is CharArray -> this[index] = value as Char
        is LongArray -> this[index] = value as Long
        is FloatArray -> this[index] = value as Float
        is ShortArray -> this[index] = value as Short
        is DoubleArray -> this[index] = value as Double
        is BooleanArray -> this[index] = value as Boolean
        is Array<*> -> (this as Array<Value>)[index] = value
        else -> error("setArrayValue: unexpected array $this")
    }
}

private val JcField.toJavaField: Field?
    get() = enclosingClass.toType().toJavaClass(JcConcreteMemoryClassLoader).allFields.find { it.name == name }

private val JcMethod.toJavaMethod: Executable?
    get() = this.toJavaExecutable(JcConcreteMemoryClassLoader)

private fun JcEnrichedVirtualMethod.getMethod(ctx: JcContext): JcMethod? {
    val originalClassName = OriginalClassName(enclosingClass.name)
    val approximationClassName =
        Approximations.findApproximationByOriginOrNull(originalClassName)
            ?: return null
    return ctx.cp.findClassOrNull(approximationClassName)
        ?.declaredMethods
        ?.find { it.name == this.name }
}

@Suppress("RecursivePropertyAccessor")
private val JcType.isEnum: Boolean
    get() = this is JcClassType && (this.jcClass.isEnum || this.superType?.isEnum == true)

private val JcType.isEnumArray: Boolean
    get() = this is JcArrayType && this.elementType.let { it is JcClassType && it.jcClass.isEnum }

private val JcType.internalName: String
    get() = if (this is JcClassType) this.name else this.typeName

private val notTrackedTypes = setOf(
    "java.lang.Class",
)

private val Class<*>.isProxy: Boolean
    get() = Proxy.isProxyClass(this)

private val Class<*>.isLambda: Boolean
    get() = typeName.contains('/') && typeName.contains("\$\$Lambda\$")

private val Class<*>.isThreadLocal: Boolean
    get() = ThreadLocal::class.java.isAssignableFrom(this)

private val Class<*>.isByteBuffer: Boolean
    get() = ByteBuffer::class.java.isAssignableFrom(this)

private val JcClassOrInterface.isException: Boolean
    get() = superClasses.any { it.name == "java.lang.Throwable" }

private val JcMethod.isExceptionCtor: Boolean
    get() = isConstructor && enclosingClass.isException

private val Class<*>.notTracked: Boolean
    get() =
        this.isPrimitive ||
                this.isEnum ||
                notTrackedTypes.contains(this.name)

private val JcType.notTracked: Boolean
    get() =
        this is JcPrimitiveType ||
                this is JcClassType &&
                (this.jcClass.isEnum || notTrackedTypes.contains(this.name))

private val immutableTypes = setOf(
    "jdk.internal.loader.ClassLoaders\$AppClassLoader",
    "java.security.AllPermission",
    "java.net.NetPermission",
)

private val packagesWithImmutableTypes = setOf("java.lang", "java.lang.reflect")

private val Class<*>.isClassLoader: Boolean
    get() = ClassLoader::class.java.isAssignableFrom(this)

private val Class<*>.isImmutable: Boolean
    get() = immutableTypes.contains(this.name) || isClassLoader || this.packageName in packagesWithImmutableTypes

//private val JcType.isClassLoader: Boolean
//    get() = this is JcClassType && this.jcClass.superClasses.any { it.name == "java.lang.ClassLoader" }

//private val JcType.isImmutable: Boolean
//    get() = this !is JcClassType || immutableTypes.contains(this.name) || isClassLoader || jcClass.packageName in packagesWithImmutableTypes

private val Class<*>.isSolid: Boolean
    get() = notTracked || isImmutable || this.isArray && this.componentType.notTracked

//private val JcType.isSolid: Boolean
//    get() = notTracked || isImmutable || this is JcArrayType && this.elementType.notTracked

private fun Class<*>.toJcType(ctx: JcContext): JcType? {
    try {
        if (isProxy) {
            val interfaces = interfaces
            if (interfaces.size == 1)
                return ctx.cp.findTypeOrNull(interfaces[0].typeName)

            return null
        }

        if (isLambda) {
            // TODO: add dynamic load of classes into jacodb
            val db = ctx.cp.db
            val vfs = db.javaClass.allInstanceFields.find { it.name == "classesVfs" }!!.getFieldValue(db)!!
            val loc =
                ctx.cp.registeredLocations.find { it.jcLocation?.jarOrFolder?.absolutePath?.startsWith("/Users/michael/Documents/Work/spring-petclinic/build/libs/BOOT-INF/classes") == true }!!
            val addMethod = vfs.javaClass.methods.find { it.name == "addClass" }!!
            val source = LazyClassSourceImpl(loc, typeName)
            addMethod.invoke(vfs, source)

            val name = typeName.split('/')[0]
            return ctx.cp.findTypeOrNull(name)
        }

        return ctx.cp.findTypeOrNull(this.typeName)
    } catch (e: Throwable) {
        return null
    }
}

//endregion

private typealias childMapType = MutableMap<ChildKind, Cell>
private typealias childrenType = MutableMap<PhysicalAddress, childMapType>
private typealias parentMapType = MutableMap<PhysicalAddress, ChildKind>
private typealias parentsType = MutableMap<PhysicalAddress, parentMapType>

private enum class JcConcreteMemoryState {
    Mutable,
    MutableWithBacktrack,
    Immutable,
    Dead
}

private data class JcConcreteMemoryStateHolder(
    var state: JcConcreteMemoryState
) {
    fun isWritable(): Boolean {
        return when (state) {
            JcConcreteMemoryState.Mutable -> true
            JcConcreteMemoryState.MutableWithBacktrack -> true
            else -> false
        }
    }

    fun isDead(): Boolean {
        return state == JcConcreteMemoryState.Dead
    }

    fun isAlive(): Boolean {
        return !isDead()
    }

    fun isBacktrack(): Boolean {
        return state == JcConcreteMemoryState.MutableWithBacktrack
    }
}

private class JcConcreteBacktrackPart {
    val backtrackChanges: MutableMap<PhysicalAddress, PhysicalAddress> = mutableMapOf()
    val backtrackStatics: MutableMap<Field, PhysicalAddress> = mutableMapOf()
    val staticsCache: MutableSet<JcClassOrInterface> = mutableSetOf()
    val forks: MutableList<JcConcreteMemoryStateHolder> = mutableListOf()
}

fun <T> ArrayDeque<T>.push(element: T): Unit = addLast(element)

fun <T> ArrayDeque<T>.pop(): T = removeLast()

fun <T> ArrayDeque<T>.peek(): T = last()

//region Concrete Memory Bindings

private class JcConcreteMemoryBindings(
    private val ctx: JcContext,
    private val typeConstraints: UTypeConstraints<JcType>,
    private val physToVirt: MutableMap<PhysicalAddress, UConcreteHeapAddress>,
    private val virtToPhys: MutableMap<UConcreteHeapAddress, PhysicalAddress>,
    val state: JcConcreteMemoryStateHolder,
    private val children: MutableMap<PhysicalAddress, childMapType>,
    private val parents: MutableMap<PhysicalAddress, parentMapType>,
    private val fullyConcretes: MutableSet<PhysicalAddress>,
    val backtrackChanges: ArrayDeque<JcConcreteBacktrackPart>,
    private val getThreadLocalValue: (threadLocal: Any) -> Any?,
    private val setThreadLocalValue: (threadLocal: Any, value: Any?) -> Unit,
) {
    constructor(
        ctx: JcContext,
        typeConstraints: UTypeConstraints<JcType>,
        getThreadLocalValue: (threadLocal: Any) -> Any?,
        setThreadLocalValue: (threadLocal: Any, value: Any?) -> Unit,
    ) : this(
        ctx,
        typeConstraints,
        mutableMapOf(),
        mutableMapOf(),
        JcConcreteMemoryStateHolder(JcConcreteMemoryState.Mutable),
        mutableMapOf(),
        mutableMapOf(),
        mutableSetOf(),
        ArrayDeque(),
        getThreadLocalValue,
        setThreadLocalValue,
    )

    init {
        JcConcreteMemoryClassLoader.cp = ctx.cp
    }

    //region Primitives

    fun typeOf(address: UConcreteHeapAddress): JcType {
        return typeConstraints.typeOf(address)
    }

    fun contains(address: UConcreteHeapAddress): Boolean {
        return virtToPhys.contains(address)
    }

    fun tryVirtToPhys(address: UConcreteHeapAddress): Any? {
        return virtToPhys[address]?.obj
    }

    fun virtToPhys(address: UConcreteHeapAddress): Any {
        return virtToPhys[address]?.obj!!
    }

    fun tryFullyConcrete(address: UConcreteHeapAddress): Any? {
        val phys = virtToPhys[address]
        if (phys != null && checkConcreteness(phys)) {
            return phys.obj
        }
        return null
    }

    fun tryPhysToVirt(obj: Any): UConcreteHeapAddress? {
        return physToVirt[PhysicalAddress(obj)]
    }

    fun makeNonWritable() {
        state.state = JcConcreteMemoryState.Immutable
    }

    private fun enableBacktrack() {
        check(state.isAlive())
        if (state.isBacktrack())
            return

        val part = JcConcreteBacktrackPart()
        part.forks.add(state)
        backtrackChanges.push(part)
    }

    fun makeMutableWithBacktrack() {
        enableBacktrack()
        state.state = JcConcreteMemoryState.MutableWithBacktrack
    }

    //endregion

    //region Concreteness Tracking

    private fun hasFullyConcreteParent(phys: PhysicalAddress): Boolean {
        val tested = mutableSetOf<PhysicalAddress>()
        val queue: Queue<PhysicalAddress> = LinkedList()
        var contains = false
        var child: PhysicalAddress? = phys

        while (!contains && child != null) {
            if (tested.add(child)) {
                contains = fullyConcretes.contains(child)
                parents[child]?.forEach {
                    val parent = it.key
                    queue.add(parent)
                }
            }
            child = queue.poll()
        }

        return contains
    }

    private fun addToParents(parent: PhysicalAddress, child: PhysicalAddress, childKind: ChildKind) {
        check(parent.obj != null)
        check(child.obj != null)
        val parentMap = parents.getOrPut(child) { mutableMapOf() }
        parentMap[parent] = childKind
    }

    private fun addChild(parent: PhysicalAddress, child: PhysicalAddress, childKind: ChildKind, update: Boolean) {
        if (parent != child) {
            if (child.isNull && update) {
                children[parent]?.remove(childKind)
            } else if (!child.isNull) {
                val childMap = children[parent]
                if (childMap != null && update) {
                    childMap[childKind] = Cell(child)
                } else if (childMap != null) {
                    val cell = childMap[childKind]
                    if (cell != null) {
                        check(!cell.isConcrete || cell.address == child)
                    } else {
                        childMap[childKind] = Cell(child)
                    }
                } else {
                    val newChildMap = mutableMapOf<ChildKind, Cell>()
                    newChildMap[childKind] = Cell(child)
                    children[parent] = newChildMap
                }

                if (update && hasFullyConcreteParent(parent) && !checkConcreteness(child))
                    removeFromFullyConcretesRec(parent)

                addToParents(parent, child, childKind)
            }
        }
    }

    private fun trackChild(parent: PhysicalAddress, child: PhysicalAddress, childKind: ChildKind) {
        addChild(parent, child, childKind, false)
    }

    private fun trackChild(parent: Any?, child: Any?, childKind: ChildKind) {
        check(parent !is PhysicalAddress && child !is PhysicalAddress)
        trackChild(PhysicalAddress(parent), PhysicalAddress(child), childKind)
    }

    private fun setChild(parent: PhysicalAddress, child: PhysicalAddress, childKind: ChildKind) {
        addChild(parent, child, childKind, true)
    }

    private fun setChild(parent: Any?, child: Any?, childKind: ChildKind) {
        check(parent !is PhysicalAddress && child !is PhysicalAddress)
        setChild(PhysicalAddress(parent), PhysicalAddress(child), childKind)
    }

    private fun checkConcreteness(phys: PhysicalAddress): Boolean {
        val tracked = mutableSetOf<PhysicalAddress>()
        return checkConcretenessRec(phys, tracked)
    }

    private fun checkConcretenessRec(phys: PhysicalAddress, tracked: MutableSet<PhysicalAddress>): Boolean {
        // TODO: cache not fully concrete objects #CM
        if (fullyConcretes.contains(phys) || !tracked.add(phys))
            return true

        var allConcrete = true
        children[phys]?.forEach {
            if (allConcrete) {
                val child = it.value.address
                allConcrete = child != null && checkConcretenessRec(child, tracked)
            }
        }

        if (allConcrete) fullyConcretes.add(phys)

        return allConcrete
    }

    fun reTrackObject(obj: Any?) {
        if (obj == null)
            return

        val queue: Queue<PhysicalAddress> = LinkedList()
        val tracked = mutableSetOf<PhysicalAddress>()
        var phys: PhysicalAddress? = PhysicalAddress(obj)

        while (phys != null) {
            if (tracked.add(phys)) {
                val current = phys.obj ?: return
                val type = current.javaClass
                when {
                    type.isSolid -> continue
                    type.isArray -> {
                        val elemType = type.componentType
                        if (elemType.notTracked) continue
                        when (current) {
                            is Array<*> -> {
                                current.forEachIndexed { i, v ->
                                    val child = PhysicalAddress(v)
                                    if (!elemType.isSolid)
                                        queue.add(child)
                                    setChild(phys!!, child, ArrayIndexChildKind(i))
                                }
                            }
                            else -> error("reTrack: unexpected array $current")
                        }
                    }

                    else -> {
                        for (field in type.allInstanceFields) {
                            try {
                                val fieldType = field.type
                                if (fieldType.notTracked) continue
                                val childObj = field.getFieldValue(current)
                                val child = PhysicalAddress(childObj)
                                if (!fieldType.isSolid)
                                    queue.add(child)
                                setChild(phys, child, FieldChildKind(field))
                            } catch (e: Exception) {
                                error("ReTrack class ${type.name} failed on field ${field.name}, cause: ${e.message}")
                            }
                        }
                    }
                }
            }
            phys = queue.poll()
        }
    }

    private fun checkTrackCopy(dstArrayType: Class<*>, dstFromIdx: Int, dstToIdx: Int): Boolean {
        check(dstFromIdx <= dstToIdx)
        val elemType = dstArrayType.componentType
        return !elemType.notTracked
    }

    private fun trackCopy(updatedDstArray: Array<*>, dstArrayType: Class<*>, dstFromIdx: Int, dstToIdx: Int) {
        if (!checkTrackCopy(dstArrayType, dstFromIdx, dstToIdx)) return

        for (i in dstFromIdx..<dstToIdx) {
            setChild(updatedDstArray, updatedDstArray[i], ArrayIndexChildKind(i))
        }
    }

    private fun removeFromFullyConcretesRec(phys: PhysicalAddress) {
        val queue: Queue<PhysicalAddress> = LinkedList()
        val removed = mutableSetOf<PhysicalAddress>()
        var child: PhysicalAddress? = phys

        while (child != null) {
            if (removed.add(phys)) {
                fullyConcretes.remove(child)
                parents[child]?.forEach {
                    val parent = it.key
                    queue.add(parent)
                }
            }
            child = queue.poll()
        }
    }

    private fun markSymbolic(phys: PhysicalAddress) {
        parents[phys]?.forEach {
            val parent = it.key
            children[parent]!![it.value] = Cell()
        }
    }

    fun symbolicMembers(address: UConcreteHeapAddress): List<ChildKind> {
        check(virtToPhys.contains(address))
        val phys = virtToPhys[address]!!
        val symbolicMembers = mutableListOf<ChildKind>()
        children[phys]?.forEach {
            val child = it.value.address
            if (child == null || !checkConcreteness(child))
                symbolicMembers.add(it.key)
        }

        return symbolicMembers
    }

    //endregion

    //region Allocation

    private fun shouldAllocate(type: JcType): Boolean {
        return !type.typeName.startsWith("org.usvm.api.") &&
                !type.typeName.startsWith("generated.") &&
                !type.typeName.startsWith("stub.") &&
                !type.typeName.startsWith("runtime.")
    }

    private val interningTypes = setOf<JcType>(
        ctx.stringType,
        ctx.classType
    )

    fun allocate(address: UConcreteHeapAddress, obj: Any, type: JcType) {
        check(address != NULL_ADDRESS)
        check(!virtToPhys.containsKey(address))
        val physicalAddress = PhysicalAddress(obj)
        virtToPhys[address] = physicalAddress
        physToVirt[physicalAddress] = address
        typeConstraints.allocate(address, type)
    }

    private fun createNewAddress(type: JcType, static: Boolean): UConcreteHeapAddress {
        if (type.isEnum || type.isEnumArray || static)
            return ctx.addressCounter.freshStaticAddress()

        return ctx.addressCounter.freshAllocatedAddress()
    }

    private fun allocate(obj: Any, type: JcType, static: Boolean): UConcreteHeapAddress {
        if (interningTypes.contains(type)) {
            val address = tryPhysToVirt(obj)
            if (address != null) {
                return address
            }
        }

        val address = createNewAddress(type, static)
        allocate(address, obj, type)
        return address
    }

    private fun allocateIfShould(obj: Any, type: JcType): UConcreteHeapAddress? {
        if (shouldAllocate(type)) {
            return allocate(obj, type, false)
        }
        return null
    }

    private fun allocateIfShould(type: JcType, static: Boolean): UConcreteHeapAddress? {
        if (shouldAllocate(type)) {
            val obj = createDefault(type) ?: return null
            return allocate(obj, type, static)
        }
        return null
    }

    fun allocate(obj: Any, type: JcType): UConcreteHeapAddress? {
        return allocateIfShould(obj, type)
    }

    fun forceAllocate(obj: Any, type: JcType): UConcreteHeapAddress {
        return allocate(obj, type, false)
    }

    class LambdaInvocationHandler : InvocationHandler {

        private var methodName: String? = null
        private var actualMethod: JcMethod? = null
        private var closureArgs: List<Any?> = listOf()

        fun init(actualMethod: JcMethod, methodName: String, args: List<Any?>) {
            check(actualMethod !is JcEnrichedVirtualMethod)
            this.methodName = methodName
            this.actualMethod = actualMethod
            closureArgs = args
        }

        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            if (methodName != null && methodName == method.name) {
                val allArgs =
                    if (args == null) closureArgs
                    else closureArgs + args
                return actualMethod!!.invoke(JcConcreteMemoryClassLoader, null, allArgs)
            }

            val newArgs = args ?: arrayOf()
            return InvocationHandler.invokeDefault(proxy, method, *newArgs)
        }
    }

    private fun createProxy(type: JcClassType): Any {
        check(type.jcClass.isInterface)
        return Proxy.newProxyInstance(
            JcConcreteMemoryClassLoader,
            arrayOf(type.toJavaClass(JcConcreteMemoryClassLoader)),
            LambdaInvocationHandler()
        )
    }

    private fun createDefault(type: JcType): Any? {
        try {
            return when (type) {
                is JcArrayType -> type.allocateInstance(JcConcreteMemoryClassLoader, 1)
                is JcClassType -> {
                    if (type.jcClass.isInterface) createProxy(type)
                    else type.allocateInstance(JcConcreteMemoryClassLoader)
                }

                is JcPrimitiveType -> null
                else -> error("JcConcreteMemoryBindings.allocateDefault: unexpected type $type")
            }
        } catch (e: Exception) {
            error("failed to allocate ${type.internalName}")
        }
    }

    fun allocateDefaultConcrete(type: JcType): UConcreteHeapAddress? {
        return allocateIfShould(type, false)
    }

    fun allocateDefaultStatic(type: JcType): UConcreteHeapAddress? {
        return allocateIfShould(type, true)
    }

    //endregion

    //region Reading

    fun readClassField(address: UConcreteHeapAddress, field: Field): Any? {
        val obj = virtToPhys(address)
        val value = field.getFieldValue(obj)

        val type = field.type
        if (!type.notTracked)
            trackChild(obj, value, FieldChildKind(field))

        return value
    }

    fun readArrayIndex(address: UConcreteHeapAddress, index: Int): Any? {
        val obj = virtToPhys(address)
        val value =
            when (obj) {
                is IntArray -> obj[index]
                is ByteArray -> obj[index]
                is CharArray -> obj[index]
                is LongArray -> obj[index]
                is FloatArray -> obj[index]
                is ShortArray -> obj[index]
                is DoubleArray -> obj[index]
                is BooleanArray -> obj[index]
                is Array<*> -> obj[index]
                is String -> obj[index]
                else -> error("JcConcreteMemoryBindings.readArrayIndex: unexpected array $obj")
            }

        val arrayType = typeConstraints.typeOf(address)
        arrayType as JcArrayType
        val elemType = arrayType.elementType
        if (!elemType.notTracked)
            trackChild(obj, value, ArrayIndexChildKind(index))

        return value
    }

    // TODO: need "GetAllArrayData"?

    fun readArrayLength(address: UConcreteHeapAddress): Int {
        return when (val obj = virtToPhys(address)) {
            is IntArray -> obj.size
            is ByteArray -> obj.size
            is CharArray -> obj.size
            is LongArray -> obj.size
            is FloatArray -> obj.size
            is ShortArray -> obj.size
            is DoubleArray -> obj.size
            is BooleanArray -> obj.size
            is Array<*> -> obj.size
            is String -> obj.length
            else -> error("JcConcreteMemoryBindings.readArrayLength: unexpected array $obj")
        }
    }

    fun readMapValue(address: UConcreteHeapAddress, key: Any?): Any? {
        val obj = virtToPhys(address)
        obj as Map<*, *>
        return obj[key]
    }

    fun readMapLength(address: UConcreteHeapAddress): Int {
        val obj = virtToPhys(address)
        obj as Map<*, *>
        return obj.size
    }

    fun checkSetContains(address: UConcreteHeapAddress, element: Any?): Boolean {
        val obj = virtToPhys(address)
        obj as Set<*>
        return obj.contains(element)
    }

    fun readInvocationHandler(address: UConcreteHeapAddress): LambdaInvocationHandler {
        val obj = virtToPhys(address)
        check(Proxy.isProxyClass(obj.javaClass))
        return Proxy.getInvocationHandler(obj) as LambdaInvocationHandler
    }

    //endregion

    //region Writing

    fun writeClassField(address: UConcreteHeapAddress, field: Field, value: Any?): Boolean {
        val isWritable = state.isWritable()
        if (isWritable) {
            val obj = virtToPhys(address)
            if (state.isBacktrack())
                // TODO: add to backtrack only one field #CM
                addObjectToBacktrack(obj)

            field.setFieldValue(obj, value)

            if (!field.type.notTracked)
                setChild(obj, value, FieldChildKind(field))
        }
        return isWritable
    }

    fun <Value> writeArrayIndex(address: UConcreteHeapAddress, index: Int, value: Value): Boolean {
        val isWritable = state.isWritable()
        if (isWritable) {
            val obj = virtToPhys(address)
            if (state.isBacktrack())
                addObjectToBacktrack(obj)

            obj.setArrayValue(index, value)

            val arrayType = typeConstraints.typeOf(address)
            arrayType as JcArrayType
            val elemType = arrayType.elementType
            if (!elemType.notTracked)
                setChild(obj, value, ArrayIndexChildKind(index))
        }
        return isWritable
    }

    @Suppress("UNCHECKED_CAST")
    fun <Value> initializeArray(address: UConcreteHeapAddress, contents: List<Pair<Int, Value>>): Boolean {
        val isWritable = state.isWritable()
        if (isWritable) {
            val obj = virtToPhys(address)
            if (state.isBacktrack())
                addObjectToBacktrack(obj)

            val arrayType = obj.javaClass
            check(arrayType.isArray)
            val elemType = arrayType.componentType
            when (obj) {
                is IntArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Int
                    }
                }

                is ByteArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Byte
                    }
                }

                is CharArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Char
                    }
                }

                is LongArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Long
                    }
                }

                is FloatArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Float
                    }
                }

                is ShortArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Short
                    }
                }

                is DoubleArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Double
                    }
                }

                is BooleanArray -> {
                    check(elemType.notTracked)
                    for ((index, value) in contents) {
                        obj[index] = value as Boolean
                    }
                }

                is Array<*> -> {
                    obj as Array<Value>
                    for ((index, value) in contents) {
                        obj[index] = value
                        if (!elemType.notTracked)
                            setChild(obj, value, ArrayIndexChildKind(index))
                    }
                }

                else -> error("JcConcreteMemoryBindings.initializeArray: unexpected array $obj")
            }
        }
        return isWritable
    }

    fun writeArrayLength(address: UConcreteHeapAddress, length: Int): Boolean {
        val arrayType = typeConstraints.typeOf(address)
        arrayType as JcArrayType
        val oldObj = virtToPhys[address]
        val newObj = arrayType.allocateInstance(JcConcreteMemoryClassLoader, length)
        virtToPhys.remove(address)
        physToVirt.remove(oldObj)
        typeConstraints.remove(address)
        allocate(address, newObj, arrayType)

        return true
    }

    @Suppress("UNCHECKED_CAST")
    fun writeMapValue(address: UConcreteHeapAddress, key: Any?, value: Any?): Boolean {
        val isWritable = state.isWritable()
        if (isWritable) {
            val obj = virtToPhys(address)
            if (state.isBacktrack())
                addObjectToBacktrack(obj)
            obj as MutableMap<Any?, Any?>
            obj[key] = value
        }
        return isWritable
    }

    @Suppress("UNCHECKED_CAST")
    fun writeMapLength(address: UConcreteHeapAddress, length: Int): Boolean {
        val obj = virtToPhys(address)
        obj as Map<Any?, Any?>
        check(obj.size == length)
        return true
    }

    @Suppress("UNCHECKED_CAST")
    fun changeSetContainsElement(address: UConcreteHeapAddress, element: Any?, contains: Boolean): Boolean {
        val isWritable = state.isWritable()
        if (isWritable) {
            val obj = virtToPhys(address)
            if (state.isBacktrack())
                addObjectToBacktrack(obj)

            obj as MutableSet<Any?>
            if (contains)
                obj.add(element)
            else
                obj.remove(element)
        }
        return isWritable
    }

    //endregion

    //region Copying

    @Suppress("UNCHECKED_CAST")
    fun arrayCopy(
        srcAddress: UConcreteHeapAddress,
        dstAddress: UConcreteHeapAddress,
        fromSrcIdx: Int,
        fromDstIdx: Int,
        toDstIdx: Int
    ): Boolean {
        val isWritable = state.isWritable()
        if (isWritable) {
            val srcArray = virtToPhys(srcAddress)
            val dstArray = virtToPhys(dstAddress)
            if (state.isBacktrack())
                addObjectToBacktrack(dstArray)

            val toSrcIdx = toDstIdx - fromDstIdx + fromSrcIdx
            val dstArrayType = dstArray.javaClass
            val dstArrayElemType = dstArrayType.componentType
            when {
                srcArray is IntArray && dstArray is IntArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is ByteArray && dstArray is ByteArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is CharArray && dstArray is CharArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is LongArray && dstArray is LongArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is FloatArray && dstArray is FloatArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is ShortArray && dstArray is ShortArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is DoubleArray && dstArray is DoubleArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is BooleanArray && dstArray is BooleanArray -> {
                    check(dstArrayElemType.notTracked)
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                }

                srcArray is Array<*> && dstArray is Array<*> -> {
                    dstArray as Array<Any?>
                    srcArray.copyInto(dstArray, fromDstIdx, fromSrcIdx, toSrcIdx)
                    trackCopy(dstArray, dstArrayType, fromDstIdx, toDstIdx)
                }

                else -> error("JcConcreteMemoryBindings.arrayCopy: unexpected arrays $srcArray, $dstArray")
            }
        }
        return isWritable
    }

    //endregion

    //region Map Merging

    @Suppress("UNCHECKED_CAST")
    fun mapMerge(srcAddress: UConcreteHeapAddress, dstAddress: UConcreteHeapAddress): Boolean {
        val isWritable = state.isWritable()
        if (isWritable) {
            val srcMap = virtToPhys(srcAddress) as MutableMap<Any, Any>
            val dstMap = virtToPhys(dstAddress) as MutableMap<Any, Any>
            if (state.isBacktrack())
                addObjectToBacktrack(dstMap)
            dstMap.putAll(srcMap)
        }

        return isWritable
    }

    //endregion

    //region Set Union

    @Suppress("UNCHECKED_CAST")
    fun setUnion(srcAddress: UConcreteHeapAddress, dstAddress: UConcreteHeapAddress): Boolean {
        val isWritable = state.isWritable()
        if (isWritable) {
            val srcSet = virtToPhys(srcAddress) as MutableSet<Any>
            val dstSet = virtToPhys(dstAddress) as MutableSet<Any>
            if (state.isBacktrack())
                addObjectToBacktrack(dstSet)
            dstSet.addAll(srcSet)
        }

        return isWritable
    }

    //endregion

    //region Backtracking

    private fun cloneObject(obj: Any): Any? {
        try {
            val type = obj.javaClass
            val jcType = type.toJcType(ctx) ?: return null
            return when {
                type.isImmutable -> null
                type.isProxy || type.isLambda -> null
                type.isByteBuffer -> null
                jcType is JcArrayType -> {
                    return when (obj) {
                        is IntArray -> obj.clone()
                        is ByteArray -> obj.clone()
                        is CharArray -> obj.clone()
                        is LongArray -> obj.clone()
                        is FloatArray -> obj.clone()
                        is ShortArray -> obj.clone()
                        is DoubleArray -> obj.clone()
                        is BooleanArray -> obj.clone()
                        is Array<*> -> obj.clone()
                        else -> error("cloneObject: unexpected array $obj")
                    }
                }
                type.allInstanceFields.isEmpty() -> null
                jcType is JcClassType -> {
                    val newObj = jcType.allocateInstance(JcConcreteMemoryClassLoader)
                    for (field in type.allInstanceFields) {
                        val value = field.getFieldValueUnsafe(obj)
                        field.setFieldValueUnsafe(newObj, value)
                    }
                    newObj
                }
                else -> null
            }
        } catch (e: Exception) {
//            println("cloneObject failed on class ${type.name}")
            return null
        }
    }

    private fun addObjectToBacktrack(oldPhys: PhysicalAddress, part: JcConcreteBacktrackPart) {
        val obj = oldPhys.obj!!
        val type = obj.javaClass
        if (type.isImmutable)
            return

        val clonedObj = cloneObject(obj) ?: return
        val clonedPhys = PhysicalAddress(clonedObj)
        part.backtrackChanges[oldPhys] = clonedPhys
    }

    fun addObjectToBacktrack(obj: Any) {
        addObjectToBacktrack(PhysicalAddress(obj), backtrackChanges.peek())
    }

    fun addObjectToBacktrackRec(obj: Any?) {
        obj ?: return
        val handledObjects: MutableSet<PhysicalAddress> = mutableSetOf()
        val queue: Queue<Any?> = LinkedList()
        queue.add(obj)
        val part = backtrackChanges.peek()
        while (queue.isNotEmpty()) {
            val current = queue.poll() ?: continue
            val currentPhys = PhysicalAddress(current)
            if (!handledObjects.add(currentPhys) || part.backtrackChanges.containsKey(currentPhys))
                continue

            val type = current.javaClass
            if (type.isThreadLocal) {
                val value = getThreadLocalValue(current)
                queue.add(value)
                part.backtrackChanges[currentPhys] = PhysicalAddress(value)
                continue
            }

            addObjectToBacktrack(currentPhys, part)
            when {
                type.isImmutable -> continue
                current is Array<*> -> queue.addAll(current)
                type.isArray -> continue
                else -> {
                    for (field in type.allInstanceFields) {
                        try {
                            queue.add(field.getFieldValue(current))
                        } catch (e: Throwable) {
//                            println("addObjectToBacktrackRec failed on class ${type.name}")
                            continue
                        }
                    }
                }
            }
        }
    }

    fun addStaticsToBacktrack(types: List<JcClassOrInterface>) {
        val part = backtrackChanges.peek()
        for (type in types) {
            if (!part.staticsCache.add(type))
                continue
            val fields = type.staticFields.mapNotNull { it.toJavaField }
            for (field in fields) {
                val value = field.getStaticFieldValue()
                addObjectToBacktrackRec(value)
                if (!field.isFinal)
                    part.backtrackStatics[field] = PhysicalAddress(value)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyBacktrack(changesToBacktrack: Map<PhysicalAddress, PhysicalAddress>) {
        for ((oldPhys, clonedPhys) in changesToBacktrack) {
            val oldObj = oldPhys.obj ?: continue
            val obj = clonedPhys.obj ?: continue
            val type = oldObj.javaClass
            check(type == obj.javaClass || type.isThreadLocal)
            when {
                type.isImmutable -> continue
                type.isThreadLocal -> setThreadLocalValue(oldObj, obj)
                type.isArray -> {
                    when {
                        obj is IntArray && oldObj is IntArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is ByteArray && oldObj is ByteArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is CharArray && oldObj is CharArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is LongArray && oldObj is LongArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is FloatArray && oldObj is FloatArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is ShortArray && oldObj is ShortArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is DoubleArray && oldObj is DoubleArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is BooleanArray && oldObj is BooleanArray -> {
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        obj is Array<*> && oldObj is Array<*> -> {
                            oldObj as Array<Any?>
                            obj.forEachIndexed { i, v ->
                                oldObj[i] = v
                            }
                        }
                        else -> error("applyBacktrack: unexpected array $obj")
                    }
                }

                else -> {
                    for (field in type.allInstanceFields) {
                        try {
                            val value = field.getFieldValue(obj)
                            field.setFieldValueUnsafe(oldObj, value)
                        } catch (e: Exception) {
                            error("applyBacktrack class ${type.name} failed on field ${field.name}, cause: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    fun applyBacktrack() {
        var flag = true
        while (flag && backtrackChanges.isNotEmpty()) {
            val part = backtrackChanges.peek()
            if (part.forks.all { it.isDead() }) {
                backtrackChanges.pop()
                applyBacktrack(part.backtrackChanges)
                // TODO: backtrack statics #CM
            } else {
                flag = false
            }
        }
    }

    //endregion

    //region Removing

    fun remove(address: UConcreteHeapAddress) {
        val phys = virtToPhys.remove(address)!!
        removeFromFullyConcretesRec(phys)
        markSymbolic(phys)
    }

    //endregion

    //region Copy

    private fun copyChildren(): MutableMap<PhysicalAddress, childMapType> {
        val newChildren = mutableMapOf<PhysicalAddress, childMapType>()
        for ((parent, childMap) in children) {
            val newChildMap = childMap.toMutableMap()
            newChildren[parent] = newChildMap
        }

        return newChildren
    }

    private fun copyParents(): MutableMap<PhysicalAddress, parentMapType> {
        val newParents = mutableMapOf<PhysicalAddress, parentMapType>()
        for ((child, parentMap) in parents) {
            val newParentMap = parentMap.toMutableMap()
            newParents[child] = newParentMap
        }

        return newParents
    }

    private fun copyBacktrackChanges(newState: JcConcreteMemoryStateHolder): ArrayDeque<JcConcreteBacktrackPart> {
        for (part in backtrackChanges) {
            part.forks.add(newState)
        }

        return ArrayDeque(backtrackChanges)
    }

    fun copy(typeConstraints: UTypeConstraints<JcType>): JcConcreteMemoryBindings {
        val newState = JcConcreteMemoryStateHolder(state.state)
        return JcConcreteMemoryBindings(
            ctx,
            typeConstraints,
            physToVirt.toMutableMap(),
            virtToPhys.toMutableMap(),
            newState,
            copyChildren(),
            copyParents(),
            fullyConcretes.toMutableSet(),
            copyBacktrackChanges(newState),
            getThreadLocalValue,
            setThreadLocalValue,
        )
    }

    //endregion
}

//endregion

//region Concrete Regions

interface JcConcreteRegion

//region Concrete Fields Region

private class JcConcreteFieldRegion<Sort : USort>(
    private val regionId: UFieldsRegionId<JcField, Sort>,
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    private var baseRegion: UFieldsRegion<JcField, Sort>,
    private val marshall: Marshall
) : UFieldsRegion<JcField, Sort>, JcConcreteRegion {

    private val jcField by lazy { regionId.field }
    private val javaField by lazy { jcField.toJavaField }
    private val isApproximation by lazy { javaField == null }
//    private val isPrimitiveApproximation by lazy { isApproximation && jcField.name == "value" }
    private val sort by lazy { regionId.sort }
    private val typedField: JcTypedField by lazy { jcField.typedField }
    private val fieldType: JcType by lazy { typedField.type }
    private val isSyntheticClassField: Boolean by lazy { jcField == ctx.classTypeSyntheticField }

    private fun writeToBase(
        key: UFieldLValue<JcField, Sort>,
        value: UExpr<Sort>,
        guard: UBoolExpr
    ) {
        baseRegion = baseRegion.write(key, value, guard) as UFieldsRegion<JcField, Sort>
    }

    @Suppress("UNCHECKED_CAST")
    override fun read(key: UFieldLValue<JcField, Sort>): UExpr<Sort> {
        check(jcField == key.field)
        val ref = key.ref
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            if (isSyntheticClassField) {
                val type = bindings.virtToPhys(address) as Class<*>
                val jcType = ctx.cp.findTypeOrNull(type.typeName)!!
                jcType as JcRefType
                val allocated = bindings.allocateDefaultConcrete(jcType)!!
                return ctx.mkConcreteHeapRef(allocated) as UExpr<Sort>
            }

            if (!isApproximation) {
                val fieldObj = bindings.readClassField(address, javaField!!)
                return marshall.objToExpr(fieldObj, fieldType) // TODO: use reflect type? #CM
            }

            marshall.encode(address)
        }

        return baseRegion.read(key)
    }

    override fun write(
        key: UFieldLValue<JcField, Sort>,
        value: UExpr<Sort>,
        guard: UBoolExpr
    ): UMemoryRegion<UFieldLValue<JcField, Sort>, Sort> {
        check(jcField == key.field)
        val ref = key.ref
        if (!isSyntheticClassField && ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            if (!isApproximation) {
                val objValue = marshall.tryExprToObj(value, fieldType)
                val writeIsConcrete = objValue.hasValue && guard.isTrue
                if (writeIsConcrete && bindings.writeClassField(address, javaField!!, objValue.value))
                    return this
            }

            marshall.unmarshallClass(address)
        }

        writeToBase(key, value, guard)

        return this
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallField(ref: UConcreteHeapRef, obj: Any) {
        val lvalue = UFieldLValue(sort, ref, jcField)
        val fieldObj = jcField.getFieldValue(obj)
        val rvalue = marshall.objToExpr<USort>(fieldObj, fieldType) as UExpr<Sort>
        writeToBase(lvalue, rvalue, ctx.trueExpr)
    }

    fun copy(bindings: JcConcreteMemoryBindings, marshall: Marshall): JcConcreteFieldRegion<Sort> {
        return JcConcreteFieldRegion(
            regionId,
            ctx,
            bindings,
            baseRegion,
            marshall
        )
    }
}

//endregion

//region Concrete Array Region

private class JcConcreteArrayRegion<Sort : USort>(
    private val regionId: UArrayRegionId<JcType, Sort, USizeSort>,
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    private var baseRegion: UArrayRegion<JcType, Sort, USizeSort>,
    private val marshall: Marshall
) : UArrayRegion<JcType, Sort, USizeSort>, JcConcreteRegion {

    private val indexType by lazy { ctx.cp.int }
    private val sort by lazy { regionId.sort }

    private fun writeToBase(
        key: UArrayIndexLValue<JcType, Sort, USizeSort>,
        value: UExpr<Sort>,
        guard: UBoolExpr
    ) {
        baseRegion = baseRegion.write(key, value, guard) as UArrayRegion<JcType, Sort, USizeSort>
    }

    override fun memcpy(
        srcRef: UHeapRef,
        dstRef: UHeapRef,
        type: JcType,
        elementSort: Sort,
        fromSrcIdx: UExpr<USizeSort>,
        fromDstIdx: UExpr<USizeSort>,
        toDstIdx: UExpr<USizeSort>,
        operationGuard: UBoolExpr
    ): UArrayRegion<JcType, Sort, USizeSort> {
        if (srcRef is UConcreteHeapRef &&
            bindings.contains(srcRef.address) &&
            dstRef is UConcreteHeapRef &&
            bindings.contains(dstRef.address)
        ) {
            val fromSrcIdxObj = marshall.tryExprToObj(fromSrcIdx, indexType)
            val fromDstIdxObj = marshall.tryExprToObj(fromDstIdx, indexType)
            val toDstIdxObj = marshall.tryExprToObj(toDstIdx, indexType)
            val isConcreteCopy =
                fromSrcIdxObj.hasValue && fromDstIdxObj.hasValue && toDstIdxObj.hasValue && operationGuard.isTrue
            val success =
                isConcreteCopy &&
                    bindings.arrayCopy(
                        srcRef.address,
                        dstRef.address,
                        fromSrcIdxObj.value as Int,
                        fromDstIdxObj.value as Int,
                        toDstIdxObj.value as Int + 1 // Incrementing 'toDstIdx' index to make it exclusive
                    )
            if (success) {
                return this
            }
        }

        if (srcRef is UConcreteHeapRef)
            marshall.unmarshallArray(srcRef.address)

        if (dstRef is UConcreteHeapRef)
            marshall.unmarshallArray(dstRef.address)

        baseRegion = baseRegion.memcpy(srcRef, dstRef, type, elementSort, fromSrcIdx, fromDstIdx, toDstIdx, operationGuard)

        return this
    }

    override fun initializeAllocatedArray(
        address: UConcreteHeapAddress,
        arrayType: JcType,
        sort: Sort,
        content: Map<UExpr<USizeSort>, UExpr<Sort>>,
        operationGuard: UBoolExpr
    ): UArrayRegion<JcType, Sort, USizeSort> {
        if (bindings.contains(address)) {
            if (operationGuard.isTrue) {
                val jcArrayType =
                    if (arrayType is JcArrayType) arrayType
                    else bindings.typeOf(address) as JcArrayType
                val elemType = jcArrayType.elementType
                val elems = content.mapNotNull { (index, value) ->
                    val idx = marshall.tryExprToObj(index, ctx.cp.int)
                    val elem = marshall.tryExprToObj(value, elemType)
                    if (idx.hasValue && elem.hasValue) (idx.value as Int) to elem.value
                    else null
                }
                if (elems.size == content.size && bindings.initializeArray(address, elems)) {
                    return this
                }
            }
            marshall.unmarshallArray(address)
        }

        baseRegion = baseRegion.initializeAllocatedArray(address, arrayType, sort, content, operationGuard)

        return this
    }

    override fun read(key: UArrayIndexLValue<JcType, Sort, USizeSort>): UExpr<Sort> {
        val ref = key.ref
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val indexObj = marshall.tryExprToObj(key.index, indexType)
            if (indexObj.hasValue) {
                val valueObj = bindings.readArrayIndex(address, indexObj.value as Int)
                val elemType = (bindings.typeOf(address) as JcArrayType).elementType
                return marshall.objToExpr(valueObj, elemType)
            }

            // TODO: do not unmarshall, optimize via GetAllArrayData #CM
            marshall.unmarshallArray(address)
        }

        return baseRegion.read(key)
    }

    override fun write(
        key: UArrayIndexLValue<JcType, Sort, USizeSort>,
        value: UExpr<Sort>,
        guard: UBoolExpr
    ): UMemoryRegion<UArrayIndexLValue<JcType, Sort, USizeSort>, Sort> {
        val ref = key.ref
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val arrayType = bindings.typeOf(address) as JcArrayType
            val valueObj = marshall.tryExprToObj(value, arrayType.elementType)
            val indexObj = marshall.tryExprToObj(key.index, indexType)
            val isConcreteWrite = valueObj.hasValue && indexObj.hasValue && guard.isTrue
            if (isConcreteWrite && bindings.writeArrayIndex(ref.address, indexObj.value as Int, valueObj.value)) {
                return this
            }

            marshall.unmarshallArray(ref.address)
        }

        writeToBase(key, value, guard)

        return this
    }

    private fun unmarshallContentsCommon(
        address: UConcreteHeapAddress,
        descriptor: JcType,
        elements: Map<UExpr<USizeSort>, UExpr<Sort>>
    ) {
        baseRegion = baseRegion.initializeAllocatedArray(address, descriptor, sort, elements, ctx.trueExpr)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: Array<*>, desc: JcType) {
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, ctx.cp.objectType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: ByteArray) {
        val elemType = ctx.cp.byte
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: ShortArray) {
        val elemType = ctx.cp.short
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: CharArray) {
        val elemType = ctx.cp.char
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: IntArray) {
        val elemType = ctx.cp.int
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: LongArray) {
        val elemType = ctx.cp.long
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: FloatArray) {
        val elemType = ctx.cp.float
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: DoubleArray) {
        val elemType = ctx.cp.double
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    @Suppress("UNCHECKED_CAST")
    fun unmarshallArray(address: UConcreteHeapAddress, obj: BooleanArray) {
        val elemType = ctx.cp.boolean
        val desc = ctx.arrayDescriptorOf(ctx.cp.arrayTypeOf(elemType))
        val elements = obj.mapIndexed { idx, value ->
            ctx.mkSizeExpr(idx) to marshall.objToExpr<USort>(value, elemType) as UExpr<Sort>
        }.toMap()
        unmarshallContentsCommon(address, desc, elements)
    }

    fun copy(bindings: JcConcreteMemoryBindings, marshall: Marshall): JcConcreteArrayRegion<Sort> {
        return JcConcreteArrayRegion(
            regionId,
            ctx,
            bindings,
            baseRegion,
            marshall
        )
    }
}

//endregion

//region Concrete Array Length Region

private class JcConcreteArrayLengthRegion(
    private val regionId: UArrayLengthsRegionId<JcType, USizeSort>,
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    private var baseRegion: UArrayLengthsRegion<JcType, USizeSort>,
    private val marshall: Marshall
) : UArrayLengthsRegion<JcType, USizeSort>, JcConcreteRegion {

    private val lengthType by lazy { ctx.cp.int }

    override fun read(key: UArrayLengthLValue<JcType, USizeSort>): UExpr<USizeSort> {
        val ref = key.ref
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val lengthObj = bindings.readArrayLength(ref.address)
            return marshall.objToExpr(lengthObj, lengthType)
        } else {
            return baseRegion.read(key)
        }
    }

    override fun write(
        key: UArrayLengthLValue<JcType, USizeSort>,
        value: UExpr<USizeSort>,
        guard: UBoolExpr
    ): UMemoryRegion<UArrayLengthLValue<JcType, USizeSort>, USizeSort> {
        val ref = key.ref
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val lengthObj = marshall.tryExprToObj(value, lengthType)
            val isConcreteWrite = lengthObj.hasValue && guard.isTrue
            if (isConcreteWrite && bindings.writeArrayLength(address, lengthObj.value as Int))
                return this

            marshall.unmarshallArray(address)
        }

        baseRegion = baseRegion.write(key, value, guard) as UArrayLengthsRegion<JcType, USizeSort>

        return this
    }

    private fun unmarshallLengthCommon(ref: UConcreteHeapRef, size: Int) {
        val key = UArrayLengthLValue(ref, regionId.arrayType, regionId.sort)
        val length = marshall.objToExpr<USizeSort>(size, lengthType)
        baseRegion = baseRegion.write(key, length, ctx.trueExpr) as UArrayLengthsRegion<JcType, USizeSort>
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: Array<*>) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: ByteArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: ShortArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: CharArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: IntArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: LongArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: FloatArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: DoubleArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: BooleanArray) {
        unmarshallLengthCommon(ref, obj.size)
    }

    fun copy(bindings: JcConcreteMemoryBindings, marshall: Marshall): JcConcreteArrayLengthRegion {
        return JcConcreteArrayLengthRegion(
            regionId,
            ctx,
            bindings,
            baseRegion,
            marshall
        )
    }
}

//endregion

//region Concrete Ref Map Region

private class JcConcreteRefMapRegion<ValueSort : USort>(
    private val regionId: URefMapRegionId<JcType, ValueSort>,
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    private var baseRegion: URefMapRegion<JcType, ValueSort>,
    private val marshall: Marshall
) : URefMapRegion<JcType, ValueSort>, JcConcreteRegion {

    private val mapType = regionId.mapType
    private val valueSort = regionId.sort

    private fun writeToBase(
        key: URefMapEntryLValue<JcType, ValueSort>,
        value: UExpr<ValueSort>,
        guard: UBoolExpr
    ) {
        baseRegion = baseRegion.write(key, value, guard) as URefMapRegion<JcType, ValueSort>
    }

    override fun merge(
        srcRef: UHeapRef,
        dstRef: UHeapRef,
        mapType: JcType,
        sort: ValueSort,
        keySet: URefSetRegion<JcType>,
        operationGuard: UBoolExpr
    ): URefMapRegion<JcType, ValueSort> {
        if (srcRef is UConcreteHeapRef &&
            bindings.contains(srcRef.address) &&
            dstRef is UConcreteHeapRef &&
            bindings.contains(dstRef.address)
        ) {
            val isConcreteCopy = operationGuard.isTrue
            if (isConcreteCopy && bindings.mapMerge(srcRef.address, dstRef.address)) {
                return this
            }
        }

        if (srcRef is UConcreteHeapRef)
            marshall.unmarshallMap(srcRef.address, mapType)

        if (dstRef is UConcreteHeapRef)
            marshall.unmarshallMap(dstRef.address, mapType)

        baseRegion = baseRegion.merge(srcRef, dstRef, mapType, sort, keySet, operationGuard)

        return this
    }

    override fun read(key: URefMapEntryLValue<JcType, ValueSort>): UExpr<ValueSort> {
        // TODO: use key.mapType ? #CM
        val ref = key.mapRef
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val objType = ctx.cp.objectType
            val keyObj = marshall.tryExprToObj(key.mapKey, objType)
            if (keyObj.hasValue) {
                val valueObj = bindings.readMapValue(address, keyObj.value)
                return marshall.objToExpr(valueObj, objType)
            }

            marshall.unmarshallMap(address, mapType)
        }

        return baseRegion.read(key)
    }

    override fun write(
        key: URefMapEntryLValue<JcType, ValueSort>,
        value: UExpr<ValueSort>,
        guard: UBoolExpr
    ): UMemoryRegion<URefMapEntryLValue<JcType, ValueSort>, ValueSort> {
        val ref = key.mapRef
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val valueObj = marshall.tryExprToObj(value, ctx.cp.objectType)
            val keyObj = marshall.tryExprToObj(key.mapKey, ctx.cp.objectType)
            val isConcreteWrite = valueObj.hasValue && keyObj.hasValue && guard.isTrue
            if (isConcreteWrite && bindings.writeMapValue(address, keyObj.value, valueObj.value)) {
                return this
            }

            marshall.unmarshallMap(address, key.mapType)
        }

        writeToBase(key, value, guard)

        return this
    }

    @Suppress("UNCHECKED_CAST")
    private fun unmarshallEntry(ref: UConcreteHeapRef, key: Any?, value: Any?) {
        // TODO: not efficient, implement via memset
        val keyType = if (key == null) ctx.cp.objectType else ctx.jcTypeOf(key)
        val valueType = if (value == null) ctx.cp.objectType else ctx.jcTypeOf(value)
        val keyExpr = marshall.objToExpr<USort>(key, keyType) as UHeapRef
        val lvalue = URefMapEntryLValue(valueSort, ref, keyExpr, mapType)
        val rvalue = marshall.objToExpr<USort>(value, valueType) as UExpr<ValueSort>
        writeToBase(lvalue, rvalue, ctx.trueExpr)
    }

    fun unmarshallContents(ref: UConcreteHeapRef, obj: Map<*, *>) {
        for ((key, value) in obj) {
            unmarshallEntry(ref, key, value)
        }
    }

    fun copy(bindings: JcConcreteMemoryBindings, marshall: Marshall): JcConcreteRefMapRegion<ValueSort> {
        return JcConcreteRefMapRegion(
            regionId,
            ctx,
            bindings,
            baseRegion,
            marshall
        )
    }
}

//endregion

//region Concrete Map Length Region

private class JcConcreteMapLengthRegion(
    private val regionId: UMapLengthRegionId<JcType, USizeSort>,
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    private var baseRegion: UMapLengthRegion<JcType, USizeSort>,
    private val marshall: Marshall
) : UMapLengthRegion<JcType, USizeSort>, JcConcreteRegion {

    private val lengthType by lazy { ctx.cp.int }

    override fun read(key: UMapLengthLValue<JcType, USizeSort>): UExpr<USizeSort> {
        val ref = key.ref
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val lengthObj = bindings.readMapLength(ref.address)
            return marshall.objToExpr(lengthObj, lengthType)
        } else {
            return baseRegion.read(key)
        }
    }

    override fun write(
        key: UMapLengthLValue<JcType, USizeSort>,
        value: UExpr<USizeSort>,
        guard: UBoolExpr
    ): UMemoryRegion<UMapLengthLValue<JcType, USizeSort>, USizeSort> {
        val ref = key.ref
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val lengthObj = marshall.tryExprToObj(value, lengthType)
            val isConcreteWrite = lengthObj.hasValue && guard.isTrue
            if (isConcreteWrite && bindings.writeMapLength(address, lengthObj.value as Int))
                return this

            marshall.unmarshallMap(address, key.mapType)
        }

        baseRegion = baseRegion.write(key, value, guard) as UMapLengthRegion<JcType, USizeSort>

        return this
    }

    private fun unmarshallLength(ref: UConcreteHeapRef, size: Int) {
        val key = UMapLengthLValue(ref, regionId.mapType, regionId.sort)
        val length = marshall.objToExpr<USizeSort>(size, lengthType)
        baseRegion = baseRegion.write(key, length, ctx.trueExpr) as UMapLengthRegion<JcType, USizeSort>
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: Map<*, *>) {
        unmarshallLength(ref, obj.size)
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: SymbolicMap<*, *>) {
        unmarshallLength(ref, obj.size())
    }

    fun unmarshallLength(ref: UConcreteHeapRef, obj: SymbolicIdentityMap<*, *>) {
        unmarshallLength(ref, obj.size())
    }

    fun copy(bindings: JcConcreteMemoryBindings, marshall: Marshall): JcConcreteMapLengthRegion {
        return JcConcreteMapLengthRegion(
            regionId,
            ctx,
            bindings,
            baseRegion,
            marshall
        )
    }
}

//endregion

//region Concrete Ref Set Region

@Suppress("UNUSED")
private class JcConcreteRefSetRegion(
    private val regionId: URefSetRegionId<JcType>,
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    private var baseRegion: URefSetRegion<JcType>,
    private val marshall: Marshall
) : URefSetRegion<JcType>, JcConcreteRegion {

    private val setType by lazy { regionId.setType }
    private val sort by lazy { regionId.sort }

    private fun writeToBase(key: URefSetEntryLValue<JcType>, value: UExpr<UBoolSort>, guard: UBoolExpr) {
        baseRegion = baseRegion.write(key, value, guard) as URefSetRegion<JcType>
    }

    override fun allocatedSetWithInputElements(setRef: UConcreteHeapAddress): UAllocatedRefSetWithInputElements<JcType> {
        // TODO: elems with input addresses (statics and symbolics)
        if (bindings.contains(setRef)) {
            marshall.unmarshallSet(setRef) // TODO: make efficient: create symbolic collection from set #CM
        }

        return baseRegion.allocatedSetWithInputElements(setRef)
    }

    override fun inputSetWithInputElements(): UInputRefSetWithInputElements<JcType> {
        return baseRegion.inputSetWithInputElements()
    }

    override fun union(srcRef: UHeapRef, dstRef: UHeapRef, operationGuard: UBoolExpr): URefSetRegion<JcType> {
        if (srcRef is UConcreteHeapRef &&
            bindings.contains(srcRef.address) &&
            dstRef is UConcreteHeapRef &&
            bindings.contains(dstRef.address)
        ) {
            val isConcreteCopy = operationGuard.isTrue
            if (isConcreteCopy && bindings.setUnion(srcRef.address, dstRef.address)) {
                return this
            }
        }

        if (srcRef is UConcreteHeapRef)
            marshall.unmarshallSet(srcRef.address)

        if (dstRef is UConcreteHeapRef)
            marshall.unmarshallSet(dstRef.address)

        baseRegion = baseRegion.union(srcRef, dstRef, operationGuard)

        return this
    }

    override fun setEntries(ref: UHeapRef): URefSetEntries<JcType> {
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            marshall.unmarshallSet(ref.address) // TODO: make efficient: create set of entries
            // TODO: set of pairs (allocatedRef, element)
        }

        return baseRegion.setEntries(ref)
    }

    override fun write(
        key: URefSetEntryLValue<JcType>,
        value: UExpr<UBoolSort>,
        guard: UBoolExpr
    ): UMemoryRegion<URefSetEntryLValue<JcType>, UBoolSort> {
        val ref = key.setRef
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val objType = ctx.cp.objectType
            val keyObj = marshall.tryExprToObj(key.setElement, objType)
            val valueObj = marshall.tryExprToObj(value, ctx.cp.boolean)
            val isConcreteWrite = valueObj.hasValue && keyObj.hasValue && guard.isTrue
            if (isConcreteWrite && bindings.changeSetContainsElement(address, keyObj.value, valueObj.value as Boolean)) {
                return this
            }

            marshall.unmarshallSet(address)
        }

        writeToBase(key, value, guard)

        return this
    }

    override fun read(key: URefSetEntryLValue<JcType>): UExpr<UBoolSort> {
        val ref = key.setRef
        if (ref is UConcreteHeapRef && bindings.contains(ref.address)) {
            val address = ref.address
            val objType = ctx.cp.objectType
            val elem = marshall.tryExprToObj(key.setElement, objType)
            if (elem.hasValue) {
                val contains = bindings.checkSetContains(address, elem.value)
                return marshall.objToExpr(contains, ctx.cp.boolean)
            }
            marshall.unmarshallSet(address)
        }

        return baseRegion.read(key)
    }

    @Suppress("UNCHECKED_CAST")
    private fun unmarshallElement(ref: UConcreteHeapRef, element: Any?) {
        val elemType = if (element == null) ctx.cp.objectType else ctx.jcTypeOf(element)
        val elemExpr = marshall.objToExpr<USort>(element, elemType) as UHeapRef
        val lvalue = URefSetEntryLValue(ref, elemExpr, setType)
        writeToBase(lvalue, ctx.trueExpr, ctx.trueExpr)
    }

    fun unmarshallContents(ref: UConcreteHeapRef, obj: Set<*>) {
        for (elem in obj) {
            unmarshallElement(ref, elem)
        }
    }

    fun copy(bindings: JcConcreteMemoryBindings, marshall: Marshall): JcConcreteRefSetRegion {
        return JcConcreteRefSetRegion(
            regionId,
            ctx,
            bindings,
            baseRegion,
            marshall
        )
    }
}

//endregion

//region Concrete Static Region

private class JcConcreteStaticFieldsRegion<Sort : USort>(
    private val regionId: JcStaticFieldRegionId<Sort>,
    private var baseRegion: JcStaticFieldsMemoryRegion<Sort>,
    private val marshall: Marshall,
    private val writtenFields: MutableSet<JcStaticFieldLValue<Sort>> = mutableSetOf()
) : JcStaticFieldsMemoryRegion<Sort>(regionId.sort), JcConcreteRegion {

    // TODO: redo #CM
    override fun read(key: JcStaticFieldLValue<Sort>): UExpr<Sort> {
        val field = key.field
        if (field is JcEnrichedVirtualField || field.name == staticFieldsInitializedFlagField.name)
            return baseRegion.read(key)

        check(JcConcreteMemoryClassLoader.isLoaded(field.enclosingClass))
        val fieldType = field.typedField.type
        val javaField = field.toJavaField!!
        val value = javaField.getStaticFieldValue()
        // TODO: differs from jcField.getFieldValue(JcConcreteMemoryClassLoader, null) #CM
//        val value = field.getFieldValue(JcConcreteMemoryClassLoader, null)
        return marshall.objToExpr(value, fieldType)
    }

    override fun write(
        key: JcStaticFieldLValue<Sort>,
        value: UExpr<Sort>,
        guard: UBoolExpr
    ): JcConcreteStaticFieldsRegion<Sort> {
        writtenFields.add(key)
        // TODO: mutate concrete statics #CM
        baseRegion = baseRegion.write(key, value, guard)
        return this
    }

    override fun mutatePrimitiveStaticFieldValuesToSymbolic(enclosingClass: JcClassOrInterface) {
        // No symbolic statics
    }

    fun fieldsWithValues(): MutableMap<JcField, UExpr<Sort>> {
        val result: MutableMap<JcField, UExpr<Sort>> = mutableMapOf()
        for (key in writtenFields) {
            val value = baseRegion.read(key)
            result[key.field] = value
        }

        return result
    }

    fun copy(marshall: Marshall): JcConcreteStaticFieldsRegion<Sort> {
        return JcConcreteStaticFieldsRegion(
            regionId,
            baseRegion,
            marshall,
            writtenFields.toMutableSet()
        )
    }
}

//endregion

//region Concrete Lambda Region

private class JcConcreteCallSiteLambdaRegion(
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    private var baseRegion: JcLambdaCallSiteMemoryRegion,
    private val marshall: Marshall
) : JcLambdaCallSiteMemoryRegion(ctx), JcConcreteRegion {

    override fun writeCallSite(callSite: JcLambdaCallSite): JcConcreteCallSiteLambdaRegion {
        val address = callSite.ref.address
        val lambda = callSite.lambda
        val maybeArgs = marshall.tryExprListToFullyConcreteList(callSite.callSiteArgs, lambda.callSiteArgTypes)
        if (bindings.contains(address) && maybeArgs.hasValue) {
            val args = maybeArgs.value!!
            val invocationHandler = bindings.readInvocationHandler(address)
            val method = lambda.actualMethod.method.method
            val actualMethod =
                if (method is JcEnrichedVirtualMethod)
                    method.getMethod(ctx) ?: error("cannot find enriched method")
                else method
            invocationHandler.init(actualMethod, lambda.callSiteMethodName, args)
        }

        baseRegion = baseRegion.writeCallSite(callSite)

        return this
    }

    override fun findCallSite(ref: UConcreteHeapRef): JcLambdaCallSite? {
        return baseRegion.findCallSite(ref)
    }

    fun copy(bindings: JcConcreteMemoryBindings, marshall: Marshall): JcConcreteCallSiteLambdaRegion {
        return JcConcreteCallSiteLambdaRegion(
            ctx,
            bindings,
            baseRegion,
            marshall
        )
    }
}

//endregion

//endregion

//region Marshall

private class Marshall(
    private val ctx: JcContext,
    private val bindings: JcConcreteMemoryBindings,
    var regionStorage: JcConcreteRegionStorage,
) {

    //region Helpers

    private val boolean by lazy { ctx.cp.boolean }
    private val byte by lazy { ctx.cp.byte }
    private val short by lazy { ctx.cp.short }
    private val char by lazy { ctx.cp.char }
    private val int by lazy { ctx.cp.int }
    private val long by lazy { ctx.cp.long }
    private val float by lazy { ctx.cp.float }
    private val double by lazy { ctx.cp.double }
    private val void by lazy { ctx.cp.void }
    private val trueExpr by lazy { ctx.trueExpr }
    private val falseExpr by lazy { ctx.falseExpr }

    private val Any?.maybe: Maybe<Any?>
        get() = Maybe(this)

    private val usvmApiSymbolicList by lazy { ctx.cp.findClassOrNull<SymbolicList<*>>()!!.toType() }
    private val javaList by lazy { ctx.cp.findClassOrNull<List<*>>()!!.toType() }
    private val usvmApiSymbolicMap by lazy { ctx.cp.findClassOrNull<SymbolicMap<*, *>>()!!.toType() }
    private val usvmApiSymbolicIdentityMap by lazy { ctx.cp.findClassOrNull<SymbolicIdentityMap<*, *>>()!!.toType() }
    private val javaMap by lazy { ctx.cp.findClassOrNull<Map<*, *>>()!!.toType() }

    //endregion

    //region Encoders

    private val encoders by lazy { loadEncoders() }

    private fun loadEncoders(): Map<JcClassOrInterface, Any> {
        val objectEncoderClass = ctx.cp.findClassOrNull(ObjectEncoder::class.java.name)!!
        return runBlocking {
            ctx.cp.hierarchyExt()
                .findSubClasses(objectEncoderClass, entireHierarchy = true, includeOwn = false)
                .mapNotNull { loadEncoder(it) }
                .toMap()
        }
    }

    private fun loadEncoder(encoder: JcClassOrInterface): Pair<JcClassOrInterface, Any>? {
        val target = encoder.annotation(EncoderFor::class.java.name)!!
        val targetCls = target.values["value"] ?: return null

        targetCls as JcClassOrInterface

        val encoderCls = JcConcreteMemoryClassLoader.loadClass(encoder)
        val encoderInstance = encoderCls.getConstructor().newInstance()

        return targetCls to encoderInstance
    }

    //endregion

    //region Expression To Object Conversion

    fun <Sort : USort> commonTryExprToObj(expr: UExpr<Sort>, type: JcType, fullyConcrete: Boolean): Maybe<Any?> {
        return when {
            expr is UNullRef -> Maybe(null)
            expr is UConcreteHeapRef && expr.address == NULL_ADDRESS -> Maybe(null)
            expr is UConcreteHeapRef && fullyConcrete ->
                bindings.tryFullyConcrete(expr.address)?.maybe ?: Maybe.empty()

            expr is UConcreteHeapRef ->
                bindings.tryVirtToPhys(expr.address)?.maybe ?: Maybe.empty()

            expr is USymbol -> Maybe.empty()
            type == boolean -> {
                when (expr) {
                    trueExpr -> true.maybe
                    falseExpr -> false.maybe
                    else -> Maybe.empty()
                }
            }

            type == byte -> {
                when (expr) {
                    is KBitVec8Value -> expr.byteValue.maybe
                    else -> Maybe.empty()
                }
            }

            type == short -> {
                when (expr) {
                    is KBitVec16Value -> expr.shortValue.maybe
                    else -> Maybe.empty()
                }
            }

            type == char -> {
                when (expr) {
                    is KBitVec16Value -> expr.shortValue.toInt().toChar().maybe
                    else -> Maybe.empty()
                }
            }

            type == int -> {
                when (expr) {
                    is KBitVec32Value -> expr.intValue.maybe
                    else -> Maybe.empty()
                }
            }

            type == long -> {
                when (expr) {
                    is KBitVec64Value -> expr.longValue.maybe
                    else -> Maybe.empty()
                }
            }

            type == float -> {
                when (expr) {
                    is KFp32Value -> expr.value.maybe
                    else -> Maybe.empty()
                }
            }

            type == double -> {
                when (expr) {
                    is KFp64Value -> expr.value.maybe
                    else -> Maybe.empty()
                }
            }

            expr is UIteExpr && expr.condition.isTrue -> commonTryExprToObj(expr.trueBranch, type, fullyConcrete)
            expr is UIteExpr && expr.condition.isFalse -> commonTryExprToObj(expr.falseBranch, type, fullyConcrete)
            expr is UIteExpr -> Maybe.empty()

            else -> error("Marshall.commonTryExprToObj: unexpected expression $expr")
        }
    }

    fun <Sort : USort> tryExprToObj(expr: UExpr<Sort>, type: JcType): Maybe<Any?> {
        return commonTryExprToObj(expr, type, false)
    }

    fun <Sort : USort> tryExprToFullyConcreteObj(expr: UExpr<Sort>, type: JcType): Maybe<Any?> {
        return commonTryExprToObj(expr, type, true)
    }

    fun tryExprListToFullyConcreteList(
        expressions: List<UExpr<*>>,
        types: List<JcType>
    ): Maybe<List<Any?>> {
        val result = mutableListOf<Any?>()
        for ((e, t) in expressions.zip(types)) {
            val obj = tryExprToFullyConcreteObj(e, t)
            if (!obj.hasValue)
                return Maybe.empty()
            result.add(obj)
        }

        return Maybe(result)
    }

    //endregion

    //region Object To Expression Conversion

    private fun typeOfObject(obj: Any): JcType? {
        return obj.javaClass.toJcType(ctx)
    }

    private fun referenceTypeToExpr(obj: Any?, type: JcRefType): UHeapRef {
        if (obj == null)
            return ctx.nullRef

        var address = bindings.tryPhysToVirt(obj)
        if (address == null) {
            val objType = typeOfObject(obj)
            val mostConcreteType = when {
                objType is JcUnknownType -> type
                objType != null && (objType.isAssignable(type) || type is JcTypeVariable) -> objType
                else -> type
            }
            address = bindings.forceAllocate(obj, mostConcreteType)
            when {
                type.isAssignable(usvmApiSymbolicList) -> unmarshallSymbolicList(address, obj)
                type.isAssignable(usvmApiSymbolicMap) -> unmarshallSymbolicMap(address, obj)
                type.isAssignable(usvmApiSymbolicIdentityMap) -> unmarshallSymbolicIdentityMap(address, obj)
            }
        }

        return ctx.mkConcreteHeapRef(address)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <Sort : USort> primitiveTypeToExpr(obj: Any): UExpr<Sort> {
        with(ctx) {
            return when (obj) {
                is Boolean -> mkBool(obj)
                is Byte -> mkBv(obj)
                is Short -> mkBv(obj)
                is Char -> mkBv(obj.code, charSort)
                is Int -> mkBv(obj)
                is Long -> mkBv(obj)
                is Float -> mkFp(obj, floatSort)
                is Double -> mkFp(obj, doubleSort)
                else -> error("Marshall.primitiveTypeToExpr: unexpected object $obj")
            } as UExpr<Sort>
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <Sort : USort> objToExpr(obj: Any?, type: JcType): UExpr<Sort> {
        return when (type) {
            void -> ctx.voidValue
            is JcPrimitiveType -> primitiveTypeToExpr(obj!!)
            is JcArrayType, is JcClassType, is JcTypeVariable -> referenceTypeToExpr(obj, type as JcRefType)
            else -> error("Marshall.objToExpr: unexpected object $obj with type ${type.typeName}")
        } as UExpr<Sort>
    }

    //endregion

    //region Unmarshall

    private fun unmarshallFields(address: UConcreteHeapAddress, obj: Any, fields: List<JcTypedField>) {
        val ref = ctx.mkConcreteHeapRef(address)
        fields.forEach {
            try {
                regionStorage.getFieldRegion(it).unmarshallField(ref, obj)
            } catch (e: Exception) {
                error("unmarshallFields failed on field ${it.name}, exception: $e")
            }
        }
    }

    fun unmarshallClass(address: UConcreteHeapAddress) {
        val obj = bindings.tryVirtToPhys(address) ?: return
        val type = bindings.typeOf(address)
        type as JcClassType
        val allFields = type.allInstanceFields
        val containsApproximationFields = allFields.any { it.field.toJavaField == null }
        if (containsApproximationFields) {
            encode(address)
            return
        }

        unmarshallFields(address, obj, allFields)
        bindings.remove(address)
    }

    private fun unmarshallArray(
        address: UConcreteHeapAddress,
        obj: Any,
        descriptor: JcType,
        elemSort: USort,
    ) {
        val ref = ctx.mkConcreteHeapRef(address)
        val arrayRegion = regionStorage.getArrayRegion(descriptor, elemSort)
        val arrayLengthRegion = regionStorage.getArrayLengthRegion(descriptor)
        when (obj) {
            is IntArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is ByteArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is CharArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is LongArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is FloatArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is ShortArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is DoubleArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is BooleanArray -> {
                arrayRegion.unmarshallArray(address, obj)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }

            is Array<*> -> {
                arrayRegion.unmarshallArray(address, obj, descriptor)
                arrayLengthRegion.unmarshallLength(ref, obj)
            }
        }
        bindings.remove(address)
    }

    fun unmarshallArray(address: UConcreteHeapAddress) {
        val obj = bindings.tryVirtToPhys(address) ?: return
        val type = bindings.typeOf(address)
        type as JcArrayType
        val desc = ctx.arrayDescriptorOf(type)
        val elemSort = ctx.typeToSort(type.elementType)
        unmarshallArray(address, obj, desc, elemSort)
    }

    private fun unmarshallMap(address: UConcreteHeapAddress, obj: Map<*, *>, mapType: JcType) {
        val ref = ctx.mkConcreteHeapRef(address)
        val valueSort = ctx.addressSort
        val mapRegion = regionStorage.getMapRegion(mapType, valueSort)
        val mapLengthRegion = regionStorage.getMapLengthRegion(mapType)
        val keySetRegion = regionStorage.getSetRegion(mapType)
        mapRegion.unmarshallContents(ref, obj)
        mapLengthRegion.unmarshallLength(ref, obj)
        keySetRegion.unmarshallContents(ref, obj.keys)
        bindings.remove(address)
    }

    fun unmarshallMap(address: UConcreteHeapAddress, mapType: JcType) {
        val obj = bindings.tryVirtToPhys(address) ?: return
        obj as Map<*, *>
        unmarshallMap(address, obj, mapType)
    }

    @Suppress("UNUSED_VARIABLE")
    fun unmarshallSet(address: UConcreteHeapAddress) {
        val obj = bindings.tryVirtToPhys(address) ?: return
        TODO("unmarshall set")
//        obj as Set<*, *>
//        unmarshallMap(address, obj, mapType)
    }

    fun unmarshallSymbolicList(address: UConcreteHeapAddress, obj: Any) {
        val methods = obj.javaClass.declaredMethods
        val getMethod = methods.find { it.name == "get" }!!
        val size = methods.find { it.name == "size" }!!.invoke(obj) as Int
        val array = Array<Any?>(size) { getMethod.invoke(obj, it) }
        unmarshallArray(address, array, usvmApiSymbolicList, ctx.addressSort)
    }

    private fun createMapFromSymbolicMap(obj: Any): Map<*, *> {
        val mapMethods = obj.javaClass.declaredMethods
        val entriesMethod = mapMethods.find { it.name == "entries" }!!
        val entries = entriesMethod.invoke(obj) as Array<*>
        val map = HashMap<Any?, Any?>()
        entries.forEach { entry ->
            val entryMethods = entry!!.javaClass.declaredMethods
            val getKeyMethod = entryMethods.find { it.name == "getKey" }!!
            val getValueMethod = entryMethods.find { it.name == "getValue" }!!
            getKeyMethod.isAccessible = true
            val key = getKeyMethod.invoke(entry)
            getValueMethod.isAccessible = true
            val value = getValueMethod.invoke(entry)
            map[key] = value
        }
        return map
    }

    fun unmarshallSymbolicMap(address: UConcreteHeapAddress, obj: Any) {
        val map = createMapFromSymbolicMap(obj)
        unmarshallMap(address, map, usvmApiSymbolicMap)
    }

    fun unmarshallSymbolicIdentityMap(address: UConcreteHeapAddress, obj: Any) {
        val map = createMapFromSymbolicMap(obj)
        unmarshallMap(address, map, usvmApiSymbolicIdentityMap)
    }

    //endregion

    //region Encoding

    fun encode(address: UConcreteHeapAddress) {
        val obj = bindings.virtToPhys(address)
        val type = bindings.typeOf(address) as JcClassType
        println("encoding for $address of ${type.name}")
        var encoder: Any? = null
        var jcClass: JcClassOrInterface? = type.jcClass
        var searchingEncoder = true
        while (jcClass != null && searchingEncoder) {
            encoder = encoders[jcClass]
            searchingEncoder = encoder == null
            jcClass = if (searchingEncoder) jcClass.superClass else jcClass
        }
        encoder ?: error("Failed to find encoder for type ${type.name}")
        val encodeMethod = encoder.javaClass.declaredMethods.find { it.name == "encode" }!!
        val approximatedObj = try {
            encodeMethod.invoke(encoder, obj)
        } catch (e: Throwable) {
            var exception = e
            if (e is InvocationTargetException)
                exception = e.targetException
            println("Encoder $encodeMethod threw exception $exception")
            throw exception
        }
        val allFields = type.allInstanceFields
        val approximationFields = allFields.filter { it.field is JcEnrichedVirtualField }
        unmarshallFields(address, approximatedObj, approximationFields)

        val otherFields = ArrayList<JcTypedField>()
        var currentClass = type
        while (currentClass.jcClass != jcClass) {
            val fields = currentClass.declaredInstanceFields
            if (fields.any { it.field is JcEnrichedVirtualField })
                error("Encoding: failed to find encoder for ${currentClass.name}")
            otherFields.addAll(fields)
            currentClass = currentClass.superType ?: error("Encoding: supertype is null")
        }
        if (otherFields.isNotEmpty())
            unmarshallFields(address, obj, otherFields)

        bindings.remove(address)
    }

    //endregion

    fun copy(bindings: JcConcreteMemoryBindings, regionStorage: JcConcreteRegionStorage): Marshall {
        return Marshall(ctx, bindings, regionStorage)
    }
}

//endregion

//region Region Storage

private interface JcConcreteRegionGetter {
    fun <Key, Sort : USort> getConcreteRegion(regionId: UMemoryRegionId<Key, Sort>): JcConcreteRegion
}

private class JcConcreteRegionStorage(
    private val ctx: JcContext,
    private val regionGetter: JcConcreteRegionGetter,
    private val fieldRegions: MutableMap<JcField, JcConcreteFieldRegion<*>> = mutableMapOf(),
    private val arrayRegions: MutableMap<JcType, JcConcreteArrayRegion<*>> = mutableMapOf(),
    private val arrayLengthRegions: MutableMap<JcType, JcConcreteArrayLengthRegion> = mutableMapOf(),
    private val mapRegions: MutableMap<JcType, JcConcreteRefMapRegion<*>> = mutableMapOf(),
    private val mapLengthRegions: MutableMap<JcType, JcConcreteMapLengthRegion> = mutableMapOf(),
    private val setRegions: MutableMap<JcType, JcConcreteRefSetRegion> = mutableMapOf(),
    private val staticFieldsRegions: MutableMap<USort, JcConcreteStaticFieldsRegion<*>> = mutableMapOf(),
    private var lambdaCallSiteRegion: JcConcreteCallSiteLambdaRegion? = null
) {

    fun getFieldRegion(typedField: JcTypedField): JcConcreteFieldRegion<*> {
        val field = typedField.field
        return fieldRegions.getOrPut(field) {
            val sort = ctx.typeToSort(typedField.type)
            val regionId = UFieldsRegionId(field, sort)
            regionGetter.getConcreteRegion(regionId) as JcConcreteFieldRegion<*>
        }
    }

    fun getFieldRegion(regionId: UFieldsRegionId<*, *>): JcConcreteFieldRegion<*> {
        return fieldRegions.getOrPut(regionId.field as JcField) {
            regionGetter.getConcreteRegion(regionId) as JcConcreteFieldRegion<*>
        }
    }

    fun getArrayRegion(desc: JcType, sort: USort): JcConcreteArrayRegion<*> {
        return arrayRegions.getOrPut(desc) {
            val regionId = UArrayRegionId<JcType, USort, USizeSort>(desc, sort)
            regionGetter.getConcreteRegion(regionId) as JcConcreteArrayRegion<*>
        }
    }

    fun getArrayRegion(regionId: UArrayRegionId<*, *, *>): JcConcreteArrayRegion<*> {
        return arrayRegions.getOrPut(regionId.arrayType as JcType) {
            regionGetter.getConcreteRegion(regionId) as JcConcreteArrayRegion<*>
        }
    }

    fun getArrayLengthRegion(desc: JcType): JcConcreteArrayLengthRegion {
        return arrayLengthRegions.getOrPut(desc) {
            val regionId = UArrayLengthsRegionId(ctx.sizeSort, desc)
            regionGetter.getConcreteRegion(regionId) as JcConcreteArrayLengthRegion
        }
    }

    fun getArrayLengthRegion(regionId: UArrayLengthsRegionId<*, *>): JcConcreteArrayLengthRegion {
        return arrayLengthRegions.getOrPut(regionId.arrayType as JcType) {
            regionGetter.getConcreteRegion(regionId) as JcConcreteArrayLengthRegion
        }
    }

    fun getMapRegion(mapType: JcType, valueSort: USort): JcConcreteRefMapRegion<*> {
        return mapRegions.getOrPut(mapType) {
            val regionId = URefMapRegionId(valueSort, mapType)
            regionGetter.getConcreteRegion(regionId) as JcConcreteRefMapRegion<*>
        }
    }

    fun getMapRegion(regionId: URefMapRegionId<*, *>): JcConcreteRefMapRegion<*> {
        return mapRegions.getOrPut(regionId.mapType as JcType) {
            regionGetter.getConcreteRegion(regionId) as JcConcreteRefMapRegion<*>
        }
    }

    fun getMapLengthRegion(mapType: JcType): JcConcreteMapLengthRegion {
        return mapLengthRegions.getOrPut(mapType) {
            val regionId = UMapLengthRegionId(ctx.sizeSort, mapType)
            regionGetter.getConcreteRegion(regionId) as JcConcreteMapLengthRegion
        }
    }

    fun getMapLengthRegion(regionId: UMapLengthRegionId<*, *>): JcConcreteMapLengthRegion {
        return mapLengthRegions.getOrPut(regionId.mapType as JcType) {
            regionGetter.getConcreteRegion(regionId) as JcConcreteMapLengthRegion
        }
    }

    fun getSetRegion(setType: JcType): JcConcreteRefSetRegion {
        return setRegions.getOrPut(setType) {
            val regionId = URefSetRegionId(setType, ctx.boolSort)
            regionGetter.getConcreteRegion(regionId) as JcConcreteRefSetRegion
        }
    }

    fun getSetRegion(regionId: URefSetRegionId<*>): JcConcreteRefSetRegion {
        return setRegions.getOrPut(regionId.setType as JcType) {
            regionGetter.getConcreteRegion(regionId) as JcConcreteRefSetRegion
        }
    }

    fun getStaticFieldsRegion(regionId: JcStaticFieldRegionId<*>): JcConcreteStaticFieldsRegion<*> {
        return staticFieldsRegions.getOrPut(regionId.sort) {
            regionGetter.getConcreteRegion(regionId) as JcConcreteStaticFieldsRegion<*>
        }
    }

    fun getLambdaCallSiteRegion(regionId: JcLambdaCallSiteRegionId): JcConcreteCallSiteLambdaRegion {
        if (lambdaCallSiteRegion != null)
            return lambdaCallSiteRegion!!
        lambdaCallSiteRegion = regionGetter.getConcreteRegion(regionId) as JcConcreteCallSiteLambdaRegion
        return lambdaCallSiteRegion!!
    }

    fun allMutatedStaticFields(): Map<JcField, UExpr<out USort>> {
        val result: MutableMap<JcField, UExpr<*>> = mutableMapOf()
        staticFieldsRegions.forEach { (_, staticRegion) ->
            result.putAll(staticRegion.fieldsWithValues())
        }

        return result
    }

    fun copy(
        bindings: JcConcreteMemoryBindings,
        regionGetter: JcConcreteRegionGetter,
        marshall: Marshall
    ): JcConcreteRegionStorage {
        val newFieldRegions: MutableMap<JcField, JcConcreteFieldRegion<*>> = mutableMapOf()
        for ((k, v) in fieldRegions)
            newFieldRegions[k] = v.copy(bindings, marshall)
        val newArrayRegions: MutableMap<JcType, JcConcreteArrayRegion<*>> = mutableMapOf()
        for ((k, v) in arrayRegions)
            newArrayRegions[k] = v.copy(bindings, marshall)
        val newArrayLengthRegions: MutableMap<JcType, JcConcreteArrayLengthRegion> = mutableMapOf()
        for ((k, v) in arrayLengthRegions)
            newArrayLengthRegions[k] = v.copy(bindings, marshall)
        val newMapRegions: MutableMap<JcType, JcConcreteRefMapRegion<*>> = mutableMapOf()
        for ((k, v) in mapRegions)
            newMapRegions[k] = v.copy(bindings, marshall)
        val newMapLengthRegions: MutableMap<JcType, JcConcreteMapLengthRegion> = mutableMapOf()
        for ((k, v) in mapLengthRegions)
            newMapLengthRegions[k] = v.copy(bindings, marshall)
        val newSetRegions: MutableMap<JcType, JcConcreteRefSetRegion> = mutableMapOf()
        for ((k, v) in setRegions)
            newSetRegions[k] = v.copy(bindings, marshall)
        val newStaticFieldsRegions: MutableMap<USort, JcConcreteStaticFieldsRegion<*>> = mutableMapOf()
        for ((k, v) in staticFieldsRegions)
            newStaticFieldsRegions[k] = v.copy(marshall)
        val newLambdaCallSiteRegion = lambdaCallSiteRegion?.copy(bindings, marshall)
        return JcConcreteRegionStorage(
            ctx,
            regionGetter,
            newFieldRegions,
            newArrayRegions,
            newArrayLengthRegions,
            newMapRegions,
            newMapLengthRegions,
            newSetRegions,
            newStaticFieldsRegions,
            newLambdaCallSiteRegion
        )
    }
}

//endregion

//region Concrete Executor

private class JcThreadFactory : ThreadFactory {

    private var count = 0

    override fun newThread(runnable: Runnable): Thread {
        check(count == 0)
        val thread = Thread(runnable)
        count++
        thread.contextClassLoader = JcConcreteMemoryClassLoader
        thread.isDaemon = true
        return thread
    }
}

class Executor {
    private val executor = Executors.newSingleThreadExecutor(JcThreadFactory())
    private val threadLocalType by lazy { ThreadLocal::class.java }

    fun execute(task: Runnable) {
        executor.submit(task).get()
    }

    fun getThreadLocalValue(threadLocal: Any): Any? {
        check(threadLocal.javaClass.isThreadLocal)
        val getMethod = threadLocalType.getMethod("get")
        var value: Any? = null
        execute {
            try {
                value = getMethod.invoke(threadLocal)
            } catch (e: Throwable) {
                error("unable to get thread local value: $e")
            }
        }

        return value
    }

    fun setThreadLocalValue(threadLocal: Any, value: Any?) {
        check(threadLocal.javaClass.isThreadLocal)
        val setMethod = threadLocalType.getMethod("set", Any::class.java)
        execute {
            try {
                setMethod.invoke(threadLocal, value)
            } catch (e: Throwable) {
                error("unable to set thread local value: $e")
            }
        }
    }
}

//endregion

//region Concrete Memory

class JcConcreteMemory private constructor(
    private val ctx: JcContext,
    typeConstraints: UTypeConstraints<JcType>,
    stack: URegistersStack,
    mocks: UIndexedMocker<JcMethod>,
    regions: PersistentMap<UMemoryRegionId<*, *>, UMemoryRegion<*, *>>,
    private val executor: Executor,
    private val bindings: JcConcreteMemoryBindings,
    private val statics: MutableList<JcClassOrInterface>,
    private var regionStorageVar: JcConcreteRegionStorage? = null,
    private var marshallVar: Marshall? = null,
) : UMemory<JcType, JcMethod>(ctx, typeConstraints, stack, mocks, regions), JcConcreteRegionGetter {

    private val ansiReset: String = "\u001B[0m"
    private val ansiBlack: String = "\u001B[30m"
    private val ansiRed: String = "\u001B[31m"
    private val ansiGreen: String = "\u001B[32m"
    private val ansiYellow: String = "\u001B[33m"
    private val ansiBlue: String = "\u001B[34m"
    private val ansiWhite: String = "\u001B[37m"
    private val ansiPurple: String = "\u001B[35m"
    private val ansiCyan: String = "\u001B[36m"

    private val regionStorage: JcConcreteRegionStorage by lazy { regionStorageVar!! }

    private val marshall: Marshall by lazy { marshallVar!! }

    private var concretization = false

    //region 'JcConcreteRegionGetter' implementation

    @Suppress("UNCHECKED_CAST")
    override fun <Key, Sort : USort> getConcreteRegion(regionId: UMemoryRegionId<Key, Sort>): JcConcreteRegion {
        val baseRegion = super.getRegion(regionId)
        return when (regionId) {
            is UFieldsRegionId<*, *> -> {
                baseRegion as UFieldsRegion<JcField, Sort>
                regionId as UFieldsRegionId<JcField, Sort>
                JcConcreteFieldRegion(regionId, ctx, bindings, baseRegion, marshall)
            }

            is UArrayRegionId<*, *, *> -> {
                baseRegion as UArrayRegion<JcType, Sort, USizeSort>
                regionId as UArrayRegionId<JcType, Sort, USizeSort>
                JcConcreteArrayRegion(regionId, ctx, bindings, baseRegion, marshall)
            }

            is UArrayLengthsRegionId<*, *> -> {
                baseRegion as UArrayLengthsRegion<JcType, USizeSort>
                regionId as UArrayLengthsRegionId<JcType, USizeSort>
                JcConcreteArrayLengthRegion(regionId, ctx, bindings, baseRegion, marshall)
            }

            is URefMapRegionId<*, *> -> {
                baseRegion as URefMapRegion<JcType, Sort>
                regionId as URefMapRegionId<JcType, Sort>
                JcConcreteRefMapRegion(regionId, ctx, bindings, baseRegion, marshall)
            }

            is UMapLengthRegionId<*, *> -> {
                baseRegion as UMapLengthRegion<JcType, USizeSort>
                regionId as UMapLengthRegionId<JcType, USizeSort>
                JcConcreteMapLengthRegion(regionId, ctx, bindings, baseRegion, marshall)
            }

            is URefSetRegionId<*> -> {
                baseRegion as URefSetRegion<JcType>
                regionId as URefSetRegionId<JcType>
                JcConcreteRefSetRegion(regionId, ctx, bindings, baseRegion, marshall)
            }

            is JcStaticFieldRegionId<*> -> {
                baseRegion as JcStaticFieldsMemoryRegion<Sort>
                val id = regionId as JcStaticFieldRegionId<Sort>
                JcConcreteStaticFieldsRegion(id, baseRegion, marshall)
            }

            is JcLambdaCallSiteRegionId -> {
                baseRegion as JcLambdaCallSiteMemoryRegion
                JcConcreteCallSiteLambdaRegion(ctx, bindings, baseRegion, marshall)
            }

            else -> baseRegion
        } as JcConcreteRegion
    }

    //endregion

    //region 'UMemory' implementation

    @Suppress("UNCHECKED_CAST")
    override fun <Key, Sort : USort> getRegion(regionId: UMemoryRegionId<Key, Sort>): UMemoryRegion<Key, Sort> {
        return when (regionId) {
            is UFieldsRegionId<*, *> -> regionStorage.getFieldRegion(regionId)
            is UArrayRegionId<*, *, *> -> regionStorage.getArrayRegion(regionId)
            is UArrayLengthsRegionId<*, *> -> regionStorage.getArrayLengthRegion(regionId)
            is UMapRegionId<*, *, *, *> -> error("ConcreteMemory.getRegion: unexpected 'UMapRegionId'")
            is URefMapRegionId<*, *> -> regionStorage.getMapRegion(regionId)
            is UMapLengthRegionId<*, *> -> regionStorage.getMapLengthRegion(regionId)
            is USetRegionId<*, *, *> -> error("ConcreteMemory.getRegion: unexpected 'USetRegionId'")
            is URefSetRegionId<*> -> regionStorage.getSetRegion(regionId)
            is JcStaticFieldRegionId<*> -> regionStorage.getStaticFieldsRegion(regionId)
            is JcLambdaCallSiteRegionId -> regionStorage.getLambdaCallSiteRegion(regionId)
            else -> super.getRegion(regionId)
        } as UMemoryRegion<Key, Sort>
    }

    override fun <Key, Sort : USort> setRegion(
        regionId: UMemoryRegionId<Key, Sort>,
        newRegion: UMemoryRegion<Key, Sort>
    ) {
        when (regionId) {
            is UFieldsRegionId<*, *> -> check(newRegion is JcConcreteFieldRegion)
            is UArrayRegionId<*, *, *> -> check(newRegion is JcConcreteArrayRegion)
            is UArrayLengthsRegionId<*, *> -> check(newRegion is JcConcreteArrayLengthRegion)
            is UMapRegionId<*, *, *, *> -> error("ConcreteMemory.setRegion: unexpected 'UMapRegionId'")
            is URefMapRegionId<*, *> -> check(newRegion is JcConcreteRefMapRegion)
            is UMapLengthRegionId<*, *> -> check(newRegion is JcConcreteMapLengthRegion)
            is USetRegionId<*, *, *> -> error("ConcreteMemory.setRegion: unexpected 'USetRegionId'")
            is URefSetRegionId<*> -> check(newRegion is JcConcreteRefSetRegion)
            is JcStaticFieldRegionId<*> -> check(newRegion is JcConcreteStaticFieldsRegion)
            is JcLambdaCallSiteRegionId -> check(newRegion is JcConcreteCallSiteLambdaRegion)
            else -> {
                super.setRegion(regionId, newRegion)
            }
        }
    }

    //endregion

    private fun allocateObject(obj: Any, type: JcType): UConcreteHeapRef {
        val address = bindings.allocate(obj, type)!!
        return ctx.mkConcreteHeapRef(address)
    }

    override fun allocConcrete(type: JcType): UConcreteHeapRef {
        val address = bindings.allocateDefaultConcrete(type)
        if (address != null)
            return ctx.mkConcreteHeapRef(address)
        return super.allocConcrete(type)
    }

    override fun allocStatic(type: JcType): UConcreteHeapRef {
        val address = bindings.allocateDefaultStatic(type)
        if (address != null)
            return ctx.mkConcreteHeapRef(address)
        return super.allocStatic(type)
    }

    override fun tryAllocateConcrete(obj: Any, type: JcType): UConcreteHeapRef? {
        val address = bindings.allocate(obj, type)
        if (address != null)
            return ctx.mkConcreteHeapRef(address)
        return null
    }

    override fun forceAllocConcrete(type: JcType): UConcreteHeapRef {
        val address = bindings.allocateDefaultConcrete(type)
        if (address != null)
            return ctx.mkConcreteHeapRef(address)
        return super.allocConcrete(type)
    }

    override fun tryHeapRefToObject(heapRef: UConcreteHeapRef): Any? {
        val maybe = marshall.tryExprToFullyConcreteObj(heapRef, ctx.cp.objectType)
        check(!(maybe.hasValue && maybe.value == null))
        if (maybe.hasValue)
            return maybe.value!!

        return null
    }

    override fun <Sort: USort> tryExprToInt(expr: UExpr<Sort>): Int? {
        val maybe = marshall.tryExprToFullyConcreteObj(expr, ctx.cp.int)
        check(!(maybe.hasValue && maybe.value == null))
        if (maybe.hasValue)
            return maybe.value!! as Int

        return null
    }

    override fun tryObjectToExpr(obj: Any?, type: JcType): UExpr<USort> {
        return marshall.objToExpr(obj, type)
    }

    override fun clone(typeConstraints: UTypeConstraints<JcType>): UMemory<JcType, JcMethod> {
        if (concretization)
            println()
        check(!concretization)

        bindings.makeNonWritable()

        val stack = stack.clone()
        val mocks = mocks.clone()
        val regions = regions.build()
        val bindings = bindings.copy(typeConstraints)
        val memory = JcConcreteMemory(
            ctx,
            typeConstraints,
            stack,
            mocks,
            regions,
            executor,
            bindings,
            statics,
            regionStorage,
            marshall
        )
        val marshall = marshall.copy(bindings, regionStorage)
        val regionStorage = regionStorage.copy(bindings, memory, marshall)
        marshall.regionStorage = regionStorage
        memory.regionStorageVar = regionStorage
        memory.marshallVar = marshall

        return memory
    }

    //region Concrete Invoke

    private fun methodIsInvokable(method: JcMethod): Boolean {
        return !(
                    method.isConstructor && method.enclosingClass.isAbstract ||
                    method.enclosingClass.isEnum && method.isConstructor ||
                    // Case for method, which exists only in approximations
                    method is JcEnrichedVirtualMethod && !method.isClassInitializer && method.toJavaMethod == null ||
                    method.humanReadableSignature.let {
                        it.startsWith("org.usvm.api.") ||
                        it.startsWith("runtime.LibSLRuntime") ||
                        it.startsWith("generated.") ||
                        it.startsWith("stub.") ||
                        forbiddenInvocations.contains(it)
                    }
                )
    }

    private val forcedInvokeMethods = setOf(
        // TODO: think about this! delete? #CM
        "java.lang.System#<clinit>():void",
    )

    private fun forceMethodInvoke(method: JcMethod): Boolean {
        return forcedInvokeMethods.contains(method.humanReadableSignature) || method.isExceptionCtor
    }

    private fun shouldInvokeClinit(method: JcMethod): Boolean {
        check(method.isClassInitializer)
        // TODO: add recursive static fields check: if static field of another class was read it should not be symbolic #CM
        return forceMethodInvoke(method) || !(method is JcEnrichedVirtualMethod && method.enclosingClass.staticFields.any { it.toJavaField == null })
    }

    private inner class JcConcretizer(
        state: JcState
    ) : JcTestStateResolver<Any?>(state.ctx, state.models.first(), state.memory, state.callStack.lastMethod().enclosingClass.toType().declaredMethods.first()) {
        override val decoderApi: JcTestInterpreterDecoderApi = JcTestInterpreterDecoderApi(ctx, JcConcreteMemoryClassLoader)

        override fun tryCreateObjectInstance(ref: UConcreteHeapRef, heapRef: UHeapRef): Any? {
            val addressInModel = ref.address

            if (bindings.contains(addressInModel)) {
                val obj = bindings.tryFullyConcrete(addressInModel)
                check(obj != null)
                return obj
            }

            if (heapRef !is UConcreteHeapRef)
                return null

            val address = heapRef.address
            val obj = bindings.tryFullyConcrete(address)
            if (obj != null)
                bindings.addObjectToBacktrackRec(obj)

            return obj
        }

        private fun resolveConcreteArray(ref: UConcreteHeapRef, type: JcArrayType): Any {
            val address = ref.address
            val obj = bindings.virtToPhys(address)
            // TODO: optimize #CM
            bindings.addObjectToBacktrackRec(obj)
            val elementType = type.elementType
            val elementSort = ctx.typeToSort(type.elementType)
            val arrayDescriptor = ctx.arrayDescriptorOf(type)
            for (kind in bindings.symbolicMembers(address)) {
                check(kind is ArrayIndexChildKind)
                val index = kind.index
                val value = readArrayIndex(ref, ctx.mkSizeExpr(index), arrayDescriptor, elementSort)
                val resolved = resolveExpr(value, elementType)
                obj.setArrayValue(index, resolved)
            }

            return obj
        }

        override fun resolveArray(ref: UConcreteHeapRef, heapRef: UHeapRef, type: JcArrayType): Any? {
            val addressInModel = ref.address
            if (heapRef is UConcreteHeapRef && bindings.contains(heapRef.address)) {
                val array = resolveConcreteArray(heapRef, type)
                saveResolvedRef(addressInModel, array)
                return array
            }

            val array = super.resolveArray(ref, heapRef, type)
            if (array != null && !bindings.contains(addressInModel)) {
                types.remove(addressInModel)
                bindings.allocate(addressInModel, array, type)
            }

            return array
        }

        private fun resolveConcreteObject(ref: UConcreteHeapRef, type: JcClassType): Any {
            val address = ref.address
            val obj = bindings.virtToPhys(address)
            // TODO: optimize #CM
            bindings.addObjectToBacktrackRec(obj)
            for (kind in bindings.symbolicMembers(address)) {
                check(kind is FieldChildKind)
                val field = kind.field
                val jcField = type.findFieldOrNull(field.name)
                    ?: error("resolveConcreteObject: can not find field $field")
                val fieldType = jcField.type
                val fieldSort = ctx.typeToSort(fieldType)
                val value = readField(ref, jcField, fieldSort)
                val resolved = resolveExpr(value, fieldType)
                field.setFieldValue(obj, resolved)
            }

            return obj
        }

        override fun resolveObject(ref: UConcreteHeapRef, heapRef: UHeapRef, type: JcClassType): Any? {
            val addressInModel = ref.address
            if (heapRef is UConcreteHeapRef && bindings.contains(heapRef.address)) {
                val obj = resolveConcreteObject(heapRef, type)
                saveResolvedRef(addressInModel, obj)
                return obj
            }

            val obj = super.resolveObject(ref, heapRef, type)
            if (obj != null && !bindings.contains(addressInModel)) {
                types.remove(addressInModel)
                bindings.allocate(addressInModel, obj, type)
            }

            return obj
        }

        override fun allocateClassInstance(type: JcClassType): Any =
            type.allocateInstance(JcConcreteMemoryClassLoader)

        override fun allocateString(value: Any?): Any = when (value) {
            is CharArray -> String(value)
            is ByteArray -> String(value)
            else -> String()
        }
    }

    private fun concretizeStatics(jcConcretizer: JcConcretizer) {
        println(ansiGreen + "Concretizing statics" + ansiReset)
        val statics = regionStorage.allMutatedStaticFields()
        // TODO: redo #CM
        statics.forEach { (field, value) ->
            val javaField = field.toJavaField
            if (javaField != null) {
                val typedField = field.typedField
                val concretizedValue = jcConcretizer.resolveExpr(value, typedField.type)
                // TODO: need to call clinit? #CM
                ensureClinit(field.enclosingClass)
                val currentValue = javaField.getStaticFieldValue()
                if (concretizedValue != currentValue)
                    javaField.setStaticFieldValue(concretizedValue)
            }
        }
    }

    private fun concretize(
        state: JcState,
        exprResolver: JcExprResolver,
        stmt: JcMethodCall,
        method: JcMethod,
    ) {
        // TODO: (1) clone recursively each object and remember it
        // TODO: (2) mutate and concretize old object
        // TODO: (3) finish current state: add new path selector (after concretization it will finish current path)
        // TODO: (4) roll back changes after path finish via remembered objects
        val concretizer = JcConcretizer(state)

        bindings.makeMutableWithBacktrack()

        bindings.addStaticsToBacktrack(statics)

        if (!concretization) {
            concretizeStatics(concretizer)
            concretization = true
        }

        val parameterInfos = method.parameters
        var parameters = stmt.arguments
        val isStatic = method.isStatic
        var thisObj: Any? = null
        val objParameters = mutableListOf<Any?>()

        if (!isStatic) {
            val thisType = method.enclosingClass.toType()
            thisObj = concretizer.withMode(ResolveMode.CURRENT) {
                resolveReference(parameters[0].asExpr(ctx.addressSort), thisType)
            }
            parameters = parameters.drop(1)
        }

        check(parameterInfos.size == parameters.size)
        for (i in parameterInfos.indices) {
            val info = parameterInfos[i]
            val value = parameters[i]
            val type = ctx.cp.findTypeOrNull(info.type)!!
            val elem = concretizer.withMode(ResolveMode.CURRENT) {
                resolveExpr(value, type)
            }
            objParameters.add(elem)
        }

        check(objParameters.size == parameters.size)
        println(ansiGreen + "Concretizing ${method.humanReadableSignature}" + ansiReset)
        invoke(state, exprResolver, stmt, method, thisObj, objParameters)
    }

    private fun ensureClinit(type: JcClassOrInterface) {
        executor.execute {
            try {
                // Loading type and executing its class initializer
                JcConcreteMemoryClassLoader.loadClass(type)
            } catch (e: Throwable) {
                error("clinit should not throw exceptions")
            }
        }
    }

    private fun unfoldException(e: Throwable): Throwable {
        return when {
            e is ExecutionException && e.cause != null -> unfoldException(e.cause!!)
            e is InvocationTargetException -> e.targetException
            else -> e
        }
    }

    private fun invoke(
        state: JcState,
        exprResolver: JcExprResolver,
        stmt: JcMethodCall,
        method: JcMethod,
        thisObj: Any?,
        objParameters: List<Any?>
    ) {
        if (bindings.state.isBacktrack()) {
            // TODO: if method is not mutating (guess via IFDS), backtrack is useless #CM
            bindings.addObjectToBacktrackRec(thisObj)
            for (arg in objParameters)
                bindings.addObjectToBacktrackRec(thisObj)
        }

        var thisArg = thisObj
        try {
            var resultObj: Any? = null
            var exception: Throwable? = null
            executor.execute {
                try {
                    resultObj = method.invoke(JcConcreteMemoryClassLoader, thisObj, objParameters)
                } catch (e: Throwable) {
                    exception = unfoldException(e)
                }
            }

            if (exception == null) {
                // No exception
                println("Result $resultObj")
                if (method.isConstructor) {
                    check(thisObj != null && resultObj != null)
                    // TODO: think about this:
                    //  A <: B
                    //  A.ctor is called symbolically, but B.ctor called concretelly #CM
                    check(thisObj.javaClass == resultObj!!.javaClass)
                    val thisAddress = bindings.tryPhysToVirt(thisObj)
                    check(thisAddress != null)
                    thisArg = resultObj
                    val type = bindings.typeOf(thisAddress)
                    bindings.remove(thisAddress)
                    types.remove(thisAddress)
                    bindings.allocate(thisAddress, resultObj!!, type)
                }

                val returnType = ctx.cp.findTypeOrNull(method.returnType)!!
                val result: UExpr<USort> = marshall.objToExpr(resultObj, returnType)
                exprResolver.ensureExprCorrectness(result, returnType)
                state.newStmt(JcConcreteInvocationResult(result, stmt))
            } else {
                // Exception thrown
                val e = exception!!
                val jcType = ctx.jcTypeOf(e)
                if (e.message == "Cannot invoke \"org.springframework.test.web.servlet.DefaultMvcResult.setModelAndView(org.springframework.web.servlet.ModelAndView)\" because \"mvcResult\" is null")
                    println()
                println("Exception ${e.javaClass} with message ${e.message}")
                val exceptionObj = allocateObject(e, jcType)
                state.throwExceptionWithoutStackFrameDrop(exceptionObj, jcType)
            }
        } finally {
            // TODO: if method is not mutating (guess via IFDS), do not reTrack #CM
            objParameters.forEach {
                bindings.reTrackObject(it)
            }
            if (thisObj != null)
                bindings.reTrackObject(thisArg)
        }
    }

    private interface TryConcreteInvokeResult

    private class TryConcreteInvokeSuccess : TryConcreteInvokeResult

    private data class TryConcreteInvokeFail(val symbolicArguments: Boolean) : TryConcreteInvokeResult

    private fun RegisteredLocation.isProjectLocation(projectLocations: List<JcByteCodeLocation>): Boolean {
        return projectLocations.any { it == jcLocation }
    }

    private fun tryConcreteInvoke(
        stmt: JcMethodCall,
        state: JcState,
        exprResolver: JcExprResolver
    ): TryConcreteInvokeResult {
        val method = stmt.method
        val arguments = stmt.arguments
        if (!methodIsInvokable(method))
            return TryConcreteInvokeFail(false)

        // TODO: delete!
//        if (method.name == "end") {
//            kill()
//            state.skipMethodInvocationWithValue(stmt, ctx.voidValue)
//            return TryConcreteInvokeSuccess()
//        }

        val signature = method.humanReadableSignature

        if (method.isClassInitializer) {
            statics.add(method.enclosingClass)
            if (!shouldInvokeClinit(method))
                // Executing clinit symbolically
                return TryConcreteInvokeFail(false)

            // Executing clinit concretely
            println(ansiGreen + "Invoking $signature" + ansiReset)
            ensureClinit(method.enclosingClass)
            state.skipMethodInvocationWithValue(stmt, ctx.voidValue)
            return TryConcreteInvokeSuccess()
        }

        if (concretization || concretizeInvocations.contains(signature)) {
            concretize(state, exprResolver, stmt, method)
            return TryConcreteInvokeSuccess()
        }

        val isWritable = bindings.state.isWritable()
        if (!isWritable && !forceMethodInvoke(method)) {
            val methodLocation = method.declaration.location
            val projectLocations = exprResolver.options.projectLocations
            val isProjectLocation =
                if (projectLocations == null) {
                    true
                } else {
                    methodLocation.isProjectLocation(projectLocations)
                }
            if (isProjectLocation)
                return TryConcreteInvokeFail(false)

            bindings.makeMutableWithBacktrack()
        }

        val parameterInfos = method.parameters
        val isStatic = method.isStatic
        var thisObj: Any? = null
        var parameters = arguments

        if (!isStatic) {
            val thisType = method.enclosingClass.toType()
            val obj = marshall.tryExprToFullyConcreteObj(arguments[0], thisType)
            if (!obj.hasValue)
                return TryConcreteInvokeFail(true)
            thisObj = obj.value
            parameters = arguments.drop(1)
        }

        val objParameters = mutableListOf<Any?>()
        check(parameterInfos.size == parameters.size)
        for (i in parameterInfos.indices) {
            val info = parameterInfos[i]
            val value = parameters[i]
            val type = ctx.cp.findTypeOrNull(info.type)!!
            val elem = marshall.tryExprToFullyConcreteObj(value, type)
            if (!elem.hasValue)
                return TryConcreteInvokeFail(true)
            objParameters.add(elem.value)
        }

        check(objParameters.size == parameters.size)
        if (bindings.state.isBacktrack()) {
            bindings.addStaticsToBacktrack(statics)
            println(ansiGreen + "Invoking (B) $signature" + ansiReset)
        } else {
            println(ansiGreen + "Invoking $signature" + ansiReset)
        }

        invoke(state, exprResolver, stmt, method, thisObj, objParameters)

        return TryConcreteInvokeSuccess()
    }

    override fun <Inst, State, Resolver> tryConcreteInvoke(stmt: Inst, state: State, exprResolver: Resolver): Boolean {
        stmt as JcMethodCall
        state as JcState
        exprResolver as JcExprResolver
        val success = tryConcreteInvoke(stmt, state, exprResolver)
        // If constructor was not invoked and arguments were symbolic, deleting default 'this' from concrete memory:
        // + No need to encode objects in inconsistent state (created via allocConcrete -- objects with default fields)
        // - During symbolic execution, 'this' may stay concrete
        if (success is TryConcreteInvokeFail && success.symbolicArguments && stmt.method.isConstructor) {
            // TODO: only if arguments are symbolic? #CM
            val thisArg = stmt.arguments[0]
            if (thisArg is UConcreteHeapRef && bindings.contains(thisArg.address))
                bindings.remove(thisArg.address)
        }

        return success is TryConcreteInvokeSuccess
    }

    fun kill() {
        bindings.state.state = JcConcreteMemoryState.Dead
        bindings.applyBacktrack()
    }

    fun enableBacktrack() {
        bindings.makeMutableWithBacktrack()
    }

    //endregion

    companion object {

        operator fun invoke(
            ctx: JcContext,
            typeConstraints: UTypeConstraints<JcType>,
        ): JcConcreteMemory {
            val executor = Executor()
            val bindings = JcConcreteMemoryBindings(
                ctx,
                typeConstraints,
                { threadLocal: Any -> executor.getThreadLocalValue(threadLocal) },
                { threadLocal: Any, value: Any? -> executor.setThreadLocalValue(threadLocal, value) }
            )
            val stack = URegistersStack()
            val mocks = UIndexedMocker<JcMethod>()
            val regions: PersistentMap<UMemoryRegionId<*, *>, UMemoryRegion<*, *>> = persistentMapOf()
            val memory = JcConcreteMemory(ctx, typeConstraints, stack, mocks, regions, executor, bindings, mutableListOf())
            val storage = JcConcreteRegionStorage(ctx, memory)
            val marshall = Marshall(ctx, bindings, storage)
            memory.regionStorageVar = storage
            memory.marshallVar = marshall
            return memory
        }

        //region Concrete Invocations

        private val forbiddenInvocations = setOf(
            "org.springframework.test.context.TestContextManager#<init>(java.lang.Class):void",
            "org.springframework.test.context.TestContextManager#<init>(org.springframework.test.context.TestContextBootstrapper):void",
            "org.springframework.boot.test.context.SpringBootTestContextBootstrapper#buildTestContext():org.springframework.test.context.TestContext",
            "org.springframework.test.context.support.AbstractTestContextBootstrapper#buildTestContext():org.springframework.test.context.TestContext",
            "org.springframework.test.context.support.AbstractTestContextBootstrapper#buildMergedContextConfiguration():org.springframework.test.context.MergedContextConfiguration",
            "org.springframework.test.context.support.AbstractTestContextBootstrapper#buildDefaultMergedContextConfiguration(java.lang.Class,org.springframework.test.context.CacheAwareContextLoaderDelegate):org.springframework.test.context.MergedContextConfiguration",
            "org.apache.commons.logging.Log#isFatalEnabled():boolean",
            "org.apache.commons.logging.Log#isErrorEnabled():boolean",
            "org.apache.commons.logging.Log#isWarnEnabled():boolean",
            "org.apache.commons.logging.Log#isInfoEnabled():boolean",
            "org.apache.commons.logging.Log#isDebugEnabled():boolean",
            "org.apache.commons.logging.Log#isTraceEnabled():boolean",
            "org.springframework.test.context.support.AbstractTestContextBootstrapper#buildMergedContextConfiguration(java.lang.Class,java.util.List,org.springframework.test.context.MergedContextConfiguration,org.springframework.test.context.CacheAwareContextLoaderDelegate,boolean):org.springframework.test.context.MergedContextConfiguration",
            "org.springframework.test.context.MergedContextConfiguration#<init>(java.lang.Class,java.lang.String[],java.lang.Class[],java.util.Set,java.lang.String[],java.util.List,java.lang.String[],java.util.Set,org.springframework.test.context.ContextLoader,org.springframework.test.context.CacheAwareContextLoaderDelegate,org.springframework.test.context.MergedContextConfiguration):void",
            "org.springframework.test.context.MergedContextConfiguration#processContextCustomizers(java.util.Set):java.util.Set",
            "org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTestContextBootstrapper#processMergedContextConfiguration(org.springframework.test.context.MergedContextConfiguration):org.springframework.test.context.MergedContextConfiguration",
            "org.springframework.boot.test.context.SpringBootTestContextBootstrapper#processMergedContextConfiguration(org.springframework.test.context.MergedContextConfiguration):org.springframework.test.context.MergedContextConfiguration",
            "org.springframework.boot.test.context.SpringBootTestContextBootstrapper#getOrFindConfigurationClasses(org.springframework.test.context.MergedContextConfiguration):java.lang.Class[]",
            "org.springframework.boot.test.context.SpringBootTestContextBootstrapper#findConfigurationClass(java.lang.Class):java.lang.Class",
            "org.springframework.boot.test.context.AnnotatedClassFinder#findFromClass(java.lang.Class):java.lang.Class",
            "org.springframework.util.ClassUtils#getPackageName(java.lang.Class):java.lang.String",
            "org.springframework.util.ClassUtils#getMainPackageName():java.lang.String",
            "java.lang.invoke.StringConcatFactory#makeConcatWithConstants(java.lang.invoke.MethodHandles\$Lookup,java.lang.String,java.lang.invoke.MethodType,java.lang.String,java.lang.Object[]):java.lang.invoke.CallSite",
            "org.apache.commons.logging.Log#info(java.lang.Object):void",
            "org.springframework.test.context.TestContextManager#getTestContext():org.springframework.test.context.TestContext",
            "org.springframework.test.context.support.DefaultTestContext#getApplicationContext():org.springframework.context.ApplicationContext",
            "org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate#loadContext(org.springframework.test.context.MergedContextConfiguration):org.springframework.context.ApplicationContext",
            "org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate#loadContextInternal(org.springframework.test.context.MergedContextConfiguration):org.springframework.context.ApplicationContext",
            "org.springframework.boot.test.context.SpringBootContextLoader#loadContext(org.springframework.test.context.MergedContextConfiguration):org.springframework.context.ApplicationContext",
            "org.springframework.boot.test.context.SpringBootContextLoader#loadContext(org.springframework.test.context.MergedContextConfiguration,org.springframework.boot.test.context.SpringBootContextLoader\$Mode,org.springframework.context.ApplicationContextInitializer):org.springframework.context.ApplicationContext",
            "org.springframework.boot.test.context.SpringBootContextLoader\$ContextLoaderHook#<init>(org.springframework.boot.test.context.SpringBootContextLoader\$Mode,org.springframework.context.ApplicationContextInitializer,java.util.function.Consumer):void",
            "org.springframework.boot.test.context.SpringBootContextLoader\$ContextLoaderHook#run(org.springframework.util.function.ThrowingSupplier):org.springframework.context.ApplicationContext",
            "org.springframework.boot.SpringApplication#withHook(org.springframework.boot.SpringApplicationHook,org.springframework.util.function.ThrowingSupplier):java.lang.Object",
            "org.springframework.boot.test.context.SpringBootContextLoader#lambda\$loadContext\$3(org.springframework.boot.SpringApplication,java.lang.String[]):org.springframework.context.ConfigurableApplicationContext",
            "org.springframework.boot.SpringApplication#run(java.lang.String[]):org.springframework.context.ConfigurableApplicationContext",
            "org.springframework.boot.SpringApplication#printBanner(org.springframework.core.env.ConfigurableEnvironment):org.springframework.boot.Banner",
            "org.springframework.boot.SpringApplication#afterRefresh(org.springframework.context.ConfigurableApplicationContext,org.springframework.boot.ApplicationArguments):void",
            "org.springframework.test.web.servlet.MockMvc#perform(org.springframework.test.web.servlet.RequestBuilder):org.springframework.test.web.servlet.ResultActions",
            "org.springframework.mock.web.MockFilterChain#doFilter(jakarta.servlet.ServletRequest,jakarta.servlet.ServletResponse):void",
            // TODO: do not invoke any filter.doFilter #CM
            "org.springframework.web.filter.OncePerRequestFilter#doFilter(jakarta.servlet.ServletRequest,jakarta.servlet.ServletResponse,jakarta.servlet.FilterChain):void",
            "org.springframework.web.filter.RequestContextFilter#doFilterInternal(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,jakarta.servlet.FilterChain):void",
            "org.springframework.web.filter.FormContentFilter#doFilterInternal(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,jakarta.servlet.FilterChain):void",
            "org.springframework.web.filter.CharacterEncodingFilter#doFilterInternal(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,jakarta.servlet.FilterChain):void",
            "org.springframework.mock.web.MockFilterChain\$ServletFilterProxy#doFilter(jakarta.servlet.ServletRequest,jakarta.servlet.ServletResponse,jakarta.servlet.FilterChain):void",
            "jakarta.servlet.http.HttpServlet#service(jakarta.servlet.ServletRequest,jakarta.servlet.ServletResponse):void",
            "org.springframework.test.web.servlet.TestDispatcherServlet#service(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "org.springframework.web.servlet.FrameworkServlet#service(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "jakarta.servlet.http.HttpServlet#service(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "org.springframework.web.servlet.FrameworkServlet#doGet(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "org.springframework.web.servlet.FrameworkServlet#processRequest(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "org.springframework.web.servlet.DispatcherServlet#doService(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "org.springframework.web.servlet.DispatcherServlet#doDispatch(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse):void",
            "org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter#handle(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,java.lang.Object):org.springframework.web.servlet.ModelAndView",
            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter#handleInternal(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,org.springframework.web.method.HandlerMethod):org.springframework.web.servlet.ModelAndView",
            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter#invokeHandlerMethod(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,org.springframework.web.method.HandlerMethod):org.springframework.web.servlet.ModelAndView",
            "org.springframework.web.method.annotation.ModelFactory#initModel(org.springframework.web.context.request.NativeWebRequest,org.springframework.web.method.support.ModelAndViewContainer,org.springframework.web.method.HandlerMethod):void",
            "org.springframework.web.method.annotation.ModelFactory#invokeModelAttributeMethods(org.springframework.web.context.request.NativeWebRequest,org.springframework.web.method.support.ModelAndViewContainer):void",
            "org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod#invokeAndHandle(org.springframework.web.context.request.ServletWebRequest,org.springframework.web.method.support.ModelAndViewContainer,java.lang.Object[]):void",
            "org.springframework.web.method.support.InvocableHandlerMethod#invokeForRequest(org.springframework.web.context.request.NativeWebRequest,org.springframework.web.method.support.ModelAndViewContainer,java.lang.Object[]):java.lang.Object",
            "org.springframework.web.method.support.InvocableHandlerMethod#getMethodArgumentValues(org.springframework.web.context.request.NativeWebRequest,org.springframework.web.method.support.ModelAndViewContainer,java.lang.Object[]):java.lang.Object[]",
            "org.springframework.web.method.support.HandlerMethodArgumentResolverComposite#resolveArgument(org.springframework.core.MethodParameter,org.springframework.web.method.support.ModelAndViewContainer,org.springframework.web.context.request.NativeWebRequest,org.springframework.web.bind.support.WebDataBinderFactory):java.lang.Object",
            "org.springframework.web.servlet.mvc.method.annotation.PathVariableMethodArgumentResolver#resolveArgument(org.springframework.core.MethodParameter,org.springframework.web.method.support.ModelAndViewContainer,org.springframework.web.context.request.NativeWebRequest,org.springframework.web.bind.support.WebDataBinderFactory):java.lang.Object",
            "org.springframework.web.method.annotation.RequestParamMethodArgumentResolver#resolveArgument(org.springframework.core.MethodParameter,org.springframework.web.method.support.ModelAndViewContainer,org.springframework.web.context.request.NativeWebRequest,org.springframework.web.bind.support.WebDataBinderFactory):java.lang.Object",
            "org.springframework.web.method.annotation.ModelAttributeMethodProcessor#resolveArgument(org.springframework.core.MethodParameter,org.springframework.web.method.support.ModelAndViewContainer,org.springframework.web.context.request.NativeWebRequest,org.springframework.web.bind.support.WebDataBinderFactory):java.lang.Object",
            "org.springframework.web.method.support.InvocableHandlerMethod#doInvoke(java.lang.Object[]):java.lang.Object",
            "java.lang.reflect.Method#invoke(java.lang.Object,java.lang.Object[]):java.lang.Object",

            "java.lang.Object#<init>():void",
            "org.springframework.util.function.ThrowingSupplier#get():java.lang.Object",
            "org.springframework.util.function.ThrowingSupplier#get(java.util.function.BiFunction):java.lang.Object",
        )

        private val concretizeInvocations = setOf(
            "org.springframework.web.servlet.DispatcherServlet#processDispatchResult(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse,org.springframework.web.servlet.HandlerExecutionChain,org.springframework.web.servlet.ModelAndView,java.lang.Exception):void",
            "org.usvm.samples.strings11.StringConcat#concretize():void",
        )

        //endregion

        //region Invariants check

        init {
            check(concretizeInvocations.intersect(forbiddenInvocations).isEmpty())
        }

        //endregion
    }
}

//endregion
