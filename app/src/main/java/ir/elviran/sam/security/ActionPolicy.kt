package ir.elviran.sam.security

enum class ActionRisk { ROUTINE_REVERSIBLE, SENSITIVE, DESTRUCTIVE, EXTERNAL }
data class ProposedAction(val description: String, val risk: ActionRisk, val execute: suspend () -> Unit)
class ActionPolicy {
    fun needsConfirmation(risk: ActionRisk): Boolean = risk != ActionRisk.ROUTINE_REVERSIBLE
    suspend fun run(action: ProposedAction, approved: Boolean = false): Result<Unit> {
        if (needsConfirmation(action.risk) && !approved) return Result.failure(ApprovalRequiredException(action.description))
        return runCatching { action.execute() }
    }
}
class ApprovalRequiredException(val actionDescription: String) : Exception("برای انجام این عملیات تأیید صریح لازم است: $actionDescription")
