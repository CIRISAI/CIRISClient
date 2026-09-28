package ai.ciris.mobile.shared.viewmodels

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CSD-021: LLMSettings turned a failed `GET /v1/system/adapters` or
 * `GET /v1/system/llm/providers` into an empty list, so "the agent has none"
 * and "the client could not ask" rendered the same sentence.
 */
class LlmSettingsReadStateTest {

    @Test
    fun aFailedReadIsAnErrorNotAnEmptyList() {
        assertEquals(LlmReadState.ERROR, llmReadState(isEmpty = true, error = "HTTP 503"))
    }

    @Test
    fun anAnsweredEmptyReadIsEmpty() {
        assertEquals(LlmReadState.EMPTY, llmReadState(isEmpty = true, error = null))
    }

    @Test
    fun aPopulatedReadIsPopulated() {
        assertEquals(LlmReadState.POPULATED, llmReadState(isEmpty = false, error = null))
    }
}
