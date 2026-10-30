package com.mediminder.app.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * RxNorm API (U.S. National Library of Medicine). Used only to standardize a
 * medicine name recognized by OCR. Fully optional — the app works offline.
 *
 * Example: GET /REST/drugs.json?name=metformi
 */
interface RxNormApi {

    @GET("drugs.json")
    suspend fun findDrugs(@Query("name") name: String): RxNormResponse
}

@Serializable
data class RxNormResponse(
    val drugGroup: RxNormDrugGroup? = null,
)

@Serializable
data class RxNormDrugGroup(
    val conceptGroup: List<RxNormConceptGroup>? = null,
)

@Serializable
data class RxNormConceptGroup(
    val conceptProperties: List<RxNormConcept>? = null,
)

@Serializable
data class RxNormConcept(
    val name: String? = null,
)
