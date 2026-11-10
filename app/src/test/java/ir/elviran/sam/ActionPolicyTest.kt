package ir.elviran.sam

import ir.elviran.sam.security.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ActionPolicyTest {
    @Test fun sensitiveActionRequiresApproval() = runBlocking {
        val policy = ActionPolicy()
        val result = policy.run(ProposedAction("حذف اطلاعات", ActionRisk.DESTRUCTIVE) {})
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ApprovalRequiredException)
    }
    @Test fun routineActionCanRunWithoutApproval() = runBlocking {
        var ran = false
        val result = ActionPolicy().run(ProposedAction("ثبت یادداشت", ActionRisk.ROUTINE_REVERSIBLE) { ran = true })
        assertTrue(result.isSuccess); assertTrue(ran)
    }
}
