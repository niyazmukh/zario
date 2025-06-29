package com.niyaz.zario.utils


enum class StudyPhase {
    /** User has registered but study hasn't formally started (e.g., waiting for conditions). */
    REGISTERED,
    /** Initial 7-day period for tracking baseline usage. */
    BASELINE,
    /** Phase where user confirms target app and goal after baseline. */
    GOAL_SETTING,
    /** Intervention active - Control group. */
    INTERVENTION_CONTROL,
    /** Intervention active - Deposit group. */
    INTERVENTION_DEPOSIT,
    /** Intervention active - Flexible Deposit group. */
    INTERVENTION_FLEXIBLE,
    /** Study completed or user withdrawn. */
    COMPLETED
}