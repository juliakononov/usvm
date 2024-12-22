package org.usvm.machine.interactive

import org.jacodb.api.jvm.JcClasspath
import org.jacodb.api.jvm.JcMethod
import org.jacodb.api.jvm.cfg.JcInst
import org.jacodb.api.jvm.ext.humanReadableSignature
import org.jacodb.api.jvm.ext.methods
import org.usvm.CoverageZone
import org.usvm.PathSelectionStrategy
import org.usvm.StateCollectionStrategy
import org.usvm.UMachineOptions
import org.usvm.api.targets.JcTarget
import org.usvm.forkblacklists.TargetsReachableForkBlackList
import org.usvm.forkblacklists.UForkBlackList
import org.usvm.machine.*
import org.usvm.machine.state.JcMethodResult
import org.usvm.machine.state.JcState
import org.usvm.machine.state.lastStmt
import org.usvm.ps.createPathSelector
import org.usvm.statistics.*
import org.usvm.statistics.collectors.AllStatesCollector
import org.usvm.statistics.collectors.CoveredNewStatesCollector
import org.usvm.statistics.collectors.TargetsReachedStatesCollector
import org.usvm.statistics.constraints.SoftConstraintsObserver
import org.usvm.statistics.distances.InterprocDistance
import org.usvm.statistics.distances.InterprocDistanceCalculator
import org.usvm.statistics.distances.MultiTargetDistanceCalculator
import org.usvm.statistics.distances.PlainCallGraphStatistics
import org.usvm.stopstrategies.createStopStrategy

class JcInteractiveMachine(
    cp: JcClasspath,
    private var options: UMachineOptions,
    private val jcMachineOptions: JcMachineOptions = JcMachineOptions(),
    private val interpreterObserver: JcInterpreterObserver? = null,
) : JcMachine(
    cp,
    options,
    jcMachineOptions,
    interpreterObserver
) {


    override fun analyze(methods: List<JcMethod>, targets: List<JcTarget>): List<JcState> {
        logger.debug("{}.analyze({})", this, methods)
        val initialStates = mutableMapOf<JcMethod, JcState>()
        methods.forEach {
            initialStates[it] = interpreter.getInitialState(it, targets)
        }

        val methodsToTrackCoverage =
            when (options.coverageZone) {
                CoverageZone.METHOD,
                CoverageZone.TRANSITIVE -> methods.toSet()
                // TODO: more adequate method filtering. !it.isConstructor is used to exclude default constructor which is often not covered
                CoverageZone.CLASS -> methods.flatMap { method ->
                    method.enclosingClass.methods.filter {
                        it.enclosingClass == method.enclosingClass && !it.isConstructor
                    }
                }.toSet() + methods
            }

        val coverageStatistics: CoverageStatistics<JcMethod, JcInst, JcState> = CoverageStatistics(
            methodsToTrackCoverage,
            applicationGraph
        )

        val callGraphStatistics =
            when (options.targetSearchDepth) {
                0u -> PlainCallGraphStatistics()
                else -> JcCallGraphStatistics(
                    options.targetSearchDepth,
                    applicationGraph,
                    typeSystem.topTypeStream(),
                    subclassesToTake = 10
                )
            }

        val transparentCfgStatistics = transparentCfgStatistics()

        val timeStatistics = TimeStatistics<JcMethod, JcState>()
        val loopTracker = JcLoopTracker()

        // create TARGETED pathSelector
        options = options.copy(pathSelectionStrategies = listOf(PathSelectionStrategy.TARGETED))
        val targetedPathSelector = createPathSelector(
            initialStates,
            options,
            applicationGraph,
            timeStatistics,
            { coverageStatistics },
            { transparentCfgStatistics },
            { callGraphStatistics },
            { loopTracker })
        val pathSelector = JcInteractivePathSelector(targetedPathSelector)

        val statesCollector =
            when (options.stateCollectionStrategy) {
                StateCollectionStrategy.COVERED_NEW -> CoveredNewStatesCollector<JcState>(coverageStatistics) {
                    it.methodResult is JcMethodResult.JcException
                }

                StateCollectionStrategy.REACHED_TARGET -> TargetsReachedStatesCollector()
                StateCollectionStrategy.ALL -> AllStatesCollector()
            }

        val stepsStatistics = StepsStatistics<JcMethod, JcState>()

        val stopStrategy = createStopStrategy(
            options,
            targets,
            timeStatisticsFactory = { timeStatistics },
            stepsStatisticsFactory = { stepsStatistics },
            coverageStatisticsFactory = { coverageStatistics },
            getCollectedStatesCount = { statesCollector.collectedStates.size }
        )

        val observers = mutableListOf<UMachineObserver<JcState>>(coverageStatistics)
        observers.add(timeStatistics)
        observers.add(stepsStatistics)

        if (interpreterObserver is UMachineObserver<*>) {
            @Suppress("UNCHECKED_CAST")
            observers.add(interpreterObserver as UMachineObserver<JcState>)
        }

        if (options.coverageZone != CoverageZone.METHOD) {
            val ignoreMethod =
                when (options.coverageZone) {
                    CoverageZone.CLASS -> { m: JcMethod -> !methodsToTrackCoverage.contains(m) }
                    CoverageZone.TRANSITIVE -> { _ -> false }
                    CoverageZone.METHOD -> throw IllegalStateException()
                }
            observers.add(
                TransitiveCoverageZoneObserver(
                    initialMethods = methodsToTrackCoverage,
                    methodExtractor = { state -> state.lastStmt.location.method },
                    addCoverageZone = { coverageStatistics.addCoverageZone(it) },
                    ignoreMethod = ignoreMethod
                )
            )
        }
        observers.add(statesCollector)
        // TODO: use the same calculator which is used for path selector
        if (targets.isNotEmpty()) {
            val distanceCalculator = MultiTargetDistanceCalculator<JcMethod, JcInst, InterprocDistance> { stmt ->
                InterprocDistanceCalculator(
                    targetLocation = stmt,
                    applicationGraph = applicationGraph,
                    cfgStatistics = cfgStatistics,
                    callGraphStatistics = callGraphStatistics
                )
            }
            interpreter.forkBlackList =
                TargetsReachableForkBlackList(distanceCalculator, shouldBlackList = { isInfinite })
        } else {
            interpreter.forkBlackList = UForkBlackList.createDefault()
        }

        if (options.useSoftConstraints) {
            observers.add(SoftConstraintsObserver())
        }

        if (logger.isInfoEnabled) {
            observers.add(
                StatisticsByMethodPrinter(
                    { methods },
                    logger::info,
                    { it.humanReadableSignature },
                    coverageStatistics,
                    timeStatistics,
                    stepsStatistics
                )
            )
        }

        if (logger.isDebugEnabled) {
            observers.add(JcDebugProfileObserver(pathSelector))
        }

        run(
            interpreter,
            pathSelector,
            observer = CompositeUMachineObserver(observers),
            isStateTerminated = ::isStateTerminated,
            stopStrategy = stopStrategy,
        )

        return statesCollector.collectedStates
    }
}