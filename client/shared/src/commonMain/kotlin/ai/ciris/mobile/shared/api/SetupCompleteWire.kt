package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.CompleteSetupRequest
import kotlinx.serialization.json.Json

/**
 * THE WIRE BODY OF `POST /v1/setup/complete` IS [CompleteSetupRequest] ITSELF.
 *
 * It used to be re-typed, field by field, into the generated SDK's
 * `SetupCompleteRequest` — a model built from an `openapi.json` that says
 * `1.0.0` and knows seventeen properties. Ours carries thirty-six, and every
 * one the SDK lacked was dropped on the floor with no error: `run_without_ai`
 * (CIRISClient#41 — the owner chose "without an AI assistant", the agent read
 * `false` and configured a brain with no LLM), `trace_analyze`,
 * `share_location_in_traces`, `preferred_language`, the five `location_*`
 * fields, `timezone`, `identity_template`, `stewardship_tier`,
 * `approved_adapters`, `org_id`. Each is a real choice the wizard collects and
 * each silently became the server's default.
 *
 * The model's property names ARE the agent's field names (snake_case, checked
 * against `routes/setup/models.py`), so encoding it directly is the contract.
 * A field added to the model reaches the wire by construction, and
 * `CompleteSetupWireTest` fails if any declared property does not.
 */
internal val SETUP_COMPLETE_JSON: Json = Json {
    // ENCODE SET VALUES, ALWAYS. With kotlinx's default `encodeDefaults=false`
    // a value that happens to equal the Kotlin default is OMITTED, and the
    // server's pydantic default wins instead. That is how a user who picked
    // exactly "admin" was created as "owner" (the SDK model defaulted
    // adminUsername to "admin"), and it is why an explicit `false` here is the
    // honest negative rather than an absence the server fills in.
    encodeDefaults = true
    // Keep omitting nulls, so genuinely-unset optional fields still take the
    // server-side default.
    explicitNulls = false
}

/** The exact bytes sent as the request body. */
fun CompleteSetupRequest.toWireJson(): String = SETUP_COMPLETE_JSON.encodeToString(CompleteSetupRequest.serializer(), this)
