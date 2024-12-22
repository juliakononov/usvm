package org.usvm.machine.interactive

import org.jacodb.api.jvm.cfg.JcInst
import org.usvm.api.targets.JcTarget

class TestTarget(override val location: JcInst? = null) : JcTarget()
