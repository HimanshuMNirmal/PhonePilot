package com.phonepilot.agent

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.phonepilot.action.ActionEngine
import com.phonepilot.action.ActionResult
import com.phonepilot.action.PhonePilotAction
import com.phonepilot.ai.SanitizedScreenContext
import com.phonepilot.model.ScreenSnapshot
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Autonomous agent orchestrator for PhonePilot.
 * Coordinates observation, planning, action execution via [ActionEngine],
 * loop detection, settlement delays, and safe user cancellation.
 */
class PhonePilotAgent(
    private val actionEngine: ActionEngine,
    private val planner: AgentPlanner,
    private val screenSnapshotProvider: () -> ScreenSnapshot?,
    private val loopDetector: LoopDetector = LoopDetector()
) {

    companion object {
        private const val TAG = "PhonePilot"
        const val DEFAULT_MAX_STEPS = 10
        const val SETTLEMENT_DELAY_MS = 1200L
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isCancelled = AtomicBoolean(false)

    @Volatile
    var currentState: TaskState = TaskState.Idle
        private set

    /**
     * Starts an autonomous multi-step task for the given [goal].
     */
    fun startTask(
        goal: String,
        maxSteps: Int = DEFAULT_MAX_STEPS,
        onStateChanged: (TaskState) -> Unit
    ) {
        if (currentState is TaskState.Running) {
            Log.w(TAG, "Cannot start task; agent is already running")
            return
        }

        isCancelled.set(false)
        val history = mutableListOf<StepRecord>()

        executor.execute {
            var step = 1
            var finished = false

            fun updateState(newState: TaskState) {
                currentState = newState
                mainHandler.post { onStateChanged(newState) }
            }

            Log.i(TAG, "Starting autonomous agent task: '$goal' (maxSteps: $maxSteps)")

            while (step <= maxSteps && !finished) {
                // 1. Check for cancellation
                if (isCancelled.get()) {
                    Log.i(TAG, "Agent task cancelled by user at step $step")
                    updateState(TaskState.Cancelled(stoppedAtStep = step, history = history.toList()))
                    finished = true
                    break
                }

                // 2. Observe current screen
                val snapshotBefore = screenSnapshotProvider()
                val screenContext = SanitizedScreenContext.from(snapshotBefore)
                val pkgBefore = snapshotBefore?.packageName

                // 3. Loop & Stuck Detection
                val loopStatus = loopDetector.check(history)
                if (loopStatus is LoopStatus.Stuck) {
                    Log.w(TAG, "LoopDetector detected stuck state at step $step: ${loopStatus.reason}")
                    updateState(
                        TaskState.Failed(
                            failedAtStep = step,
                            reason = loopStatus.reason,
                            history = history.toList()
                        )
                    )
                    finished = true
                    break
                }

                // 4. Multi-step Planning Decision
                val decision = try {
                    planner.planNextStep(goal, screenContext, history.toList())
                } catch (e: Exception) {
                    Log.e(TAG, "Error in planner.planNextStep: ${e.message}", e)
                    AgentDecision.GoalUnachievable("Planner error: ${e.message}")
                }

                // 5. Evaluate Decision
                when (decision) {
                    is AgentDecision.GoalAchieved -> {
                        Log.i(TAG, "Goal achieved at step $step: ${decision.summary}")
                        updateState(
                            TaskState.Success(
                                totalSteps = history.size,
                                summary = decision.summary,
                                history = history.toList()
                            )
                        )
                        finished = true
                        break
                    }

                    is AgentDecision.GoalUnachievable -> {
                        Log.w(TAG, "Goal unachievable at step $step: ${decision.reason}")
                        updateState(
                            TaskState.Failed(
                                failedAtStep = step,
                                reason = decision.reason,
                                history = history.toList()
                            )
                        )
                        finished = true
                        break
                    }

                    is AgentDecision.NextAction -> {
                        val action = decision.action
                        val reasoning = decision.reasoning

                        Log.i(TAG, "Step $step/$maxSteps: executing ${action::class.simpleName} ($reasoning)")
                        updateState(
                            TaskState.Running(
                                step = step,
                                maxSteps = maxSteps,
                                currentGoal = goal,
                                lastAction = action,
                                statusMessage = reasoning
                            )
                        )

                        // 6. Execute action strictly via ActionEngine
                        val result = actionEngine.execute(action)

                        // 7. Settlement delay for animations and screen updates
                        try {
                            Thread.sleep(SETTLEMENT_DELAY_MS)
                        } catch (_: InterruptedException) {
                            // Interrupted during sleep
                        }

                        // 8. Capture screen after execution to record state transition
                        val snapshotAfter = screenSnapshotProvider()
                        val pkgAfter = snapshotAfter?.packageName

                        val record = StepRecord(
                            stepNumber = step,
                            action = action,
                            actionResult = result,
                            reasoning = reasoning,
                            packageNameBefore = pkgBefore,
                            packageNameAfter = pkgAfter
                        )
                        history.add(record)

                        step++
                    }
                }
            }

            // If max steps reached without resolution
            if (!finished && !isCancelled.get()) {
                Log.w(TAG, "Agent reached max steps ($maxSteps) without achieving goal")
                updateState(
                    TaskState.Failed(
                        failedAtStep = maxSteps,
                        reason = "Maximum step limit ($maxSteps) reached without achieving goal",
                        history = history.toList()
                    )
                )
            }
        }
    }

    /**
     * Instantly requests cancellation of any active task.
     */
    fun cancelTask() {
        if (currentState is TaskState.Running) {
            Log.i(TAG, "Agent cancel requested by user")
            isCancelled.set(true)
        }
    }
}
