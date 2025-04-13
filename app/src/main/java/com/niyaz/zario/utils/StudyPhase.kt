package com.niyaz.zario // Ensure correct package

/**
 * Represents the distinct phases a participant progresses through in the Zario study.
 * These phases control application behavior, data collection, and intervention mechanics.
 */
enum class StudyPhase {
    /**
     * Initial state after successful account creation but before the baseline tracking period begins.
     * User profile exists, but no active tracking or intervention is applied.
     */
    REGISTERED,

    /**
     * The initial data collection period (typically 7 days as defined in Constants) where
     * the participant's normal app usage is tracked without intervention to establish a baseline.
     */
    BASELINE,

    /**
     * A brief phase occurring immediately after the baseline period. The participant is presented
     * with their baseline usage data and selects their target application for usage reduction.
     */
    GOAL_SETTING,

    /**
     * The main intervention period where the participant is assigned to the Control condition.
     * They aim to meet their daily usage goal for the target app and receive points for success only.
     */
    INTERVENTION_CONTROL,

    /**
     * The main intervention period where the participant is assigned to the fixed Deposit condition.
     * They aim to meet their daily usage goal, earning points for success and losing a fixed amount for failure.
     */
    INTERVENTION_DEPOSIT,

    /**
     * The main intervention period where the participant is assigned to the Flexible Deposit condition.
     * They choose their own point stakes (within limits) for success and failure at the start of this phase.
     */
    INTERVENTION_FLEXIBLE,

    /**
     * The final state indicating the participant has finished the required intervention period or has withdrawn.
     * Tracking and intervention mechanics are typically stopped.
     */
    COMPLETED
}