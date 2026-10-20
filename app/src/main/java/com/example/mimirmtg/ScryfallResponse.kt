package com.example.mimirmtg

import retrofit2.http.GET
import retrofit2.http.Query

data class ScryfallResponse(
    val name: String? = null,
    val mana_cost: String? = null,
    val type_line: String? = null,
    val oracle_text: String? = null,
    val power: String? = null,
    val toughness: String? = null,
    val loyalty: String? = null,
    val flavor_text: String? = null,
    val image_uris: ImageUris? = null,
    val card_faces: List<CardFace>? = null
)

data class CardFace(
    val name: String? = null,
    val mana_cost: String? = null,
    val type_line: String? = null,
    val oracle_text: String? = null,
    val power: String? = null,
    val toughness: String? = null,
    val loyalty: String? = null,
    val flavor_text: String? = null,
    val image_uris: ImageUris? = null
)

data class ImageUris(
    val normal: String
)

interface ScryfallApi {
    @GET("cards/random")
    suspend fun getCard(@Query("q") query: String): ScryfallResponse
}
