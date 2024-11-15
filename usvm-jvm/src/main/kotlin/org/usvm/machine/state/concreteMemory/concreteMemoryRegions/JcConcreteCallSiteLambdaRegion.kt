package org.usvm.machine.state.concreteMemory.concreteMemoryRegions

import org.jacodb.approximation.JcEnrichedVirtualMethod
import org.usvm.UConcreteHeapRef
import org.usvm.machine.JcContext
import org.usvm.machine.interpreter.JcLambdaCallSite
import org.usvm.machine.interpreter.JcLambdaCallSiteMemoryRegion
import org.usvm.machine.state.concreteMemory.JcConcreteMemoryBindings
import org.usvm.machine.state.concreteMemory.Marshall
import org.usvm.machine.state.concreteMemory.getMethod

internal class JcConcreteCallSiteLambdaRegion(
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
        } else {
            bindings.remove(address)
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
