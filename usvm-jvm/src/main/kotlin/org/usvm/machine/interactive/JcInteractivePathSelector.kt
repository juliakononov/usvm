package org.usvm.machine.interactive

import org.usvm.UPathSelector
import org.usvm.api.util.JcTestStateResolver
import org.usvm.machine.state.JcState


class JcInteractivePathSelector(
    private val pathSelector: UPathSelector<JcState>
) : UPathSelector<JcState> {

    override fun isEmpty(): Boolean = pathSelector.isEmpty()

    override fun peek(): JcState {
        //TODO("добавить sleep пока не придет следующая команда")
        return pathSelector.peek()
    }

    override fun update(state: JcState) {
        pathSelector.update(state)
        val isr = JcInteractiveStateResolver(state)
        val params = isr.withMode(JcTestStateResolver.ResolveMode.CURRENT) {
            (this as JcInteractiveStateResolver).getParams()
        }
        for (p in params) {
            println(p)
        }
    }

    override fun add(states: Collection<JcState>) = pathSelector.add(states)

    override fun remove(state: JcState) = pathSelector.remove(state)
}