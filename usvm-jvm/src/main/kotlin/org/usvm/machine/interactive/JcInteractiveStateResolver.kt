package org.usvm.machine.interactive

import org.jacodb.api.jvm.JcClassType
import org.jacodb.api.jvm.JcTypedMethodParameter
import org.usvm.api.util.JcConcreteMemoryClassLoader
import org.usvm.api.util.JcTestInterpreterDecoderApi
import org.usvm.api.util.JcTestStateResolver
import org.usvm.api.util.Reflection.allocateInstance
import org.usvm.machine.state.JcState
import org.usvm.machine.state.concreteMemory.toTypedMethod

class JcInteractiveStateResolver(
    state: JcState
) : JcTestStateResolver<Any?>(
    state.ctx,
    state.models.first(),
    state.memory,
    state.callStack.lastMethod().toTypedMethod
) {
    override val decoderApi: JcTestInterpreterDecoderApi = JcTestInterpreterDecoderApi(ctx, JcConcreteMemoryClassLoader)

    override fun allocateClassInstance(type: JcClassType): Any =
        type.allocateInstance(JcConcreteMemoryClassLoader)

    override fun allocateString(value: Any?): Any = when (value) {
        is CharArray -> String(value)
        is ByteArray -> String(value)
        else -> String()
    }

    fun getParams(): List<Pair<JcTypedMethodParameter, Any?>> {
        val params = method.parameters
        return resolveParameters().mapIndexed({ idx, param -> params[idx] to param })
    }
}