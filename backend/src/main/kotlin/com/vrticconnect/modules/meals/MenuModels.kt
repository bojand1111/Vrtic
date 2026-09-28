package com.vrticconnect.modules.meals

import kotlinx.serialization.Serializable

@Serializable
data class MenuItemInput(
    val mealSlot: String? = null,
    val description: String? = null,
    val allergenTags: List<String>? = null,
    val sortOrder: Int? = null,
)

/** docs/openapi.yaml MenuDayCreate. */
@Serializable
data class MenuDayCreate(
    val locationId: String? = null,
    val menuDate: String? = null,
    val note: String? = null,
    val items: List<MenuItemInput>? = null,
)

/** docs/openapi.yaml MenuDayUpdate (full replacement of note and items). */
@Serializable
data class MenuDayUpdate(
    val note: String? = null,
    val items: List<MenuItemInput>? = null,
)

@Serializable
data class MenuItemDto(
    val id: String,
    val mealSlot: String,
    val description: String,
    val allergenTags: List<String>,
    val sortOrder: Int,
)

/** docs/openapi.yaml MenuDay. */
@Serializable
data class MenuDayDto(
    val id: String,
    val organizationId: String,
    val locationId: String?,
    val menuDate: String,
    val isPublished: Boolean,
    val note: String?,
    val items: List<MenuItemDto>,
    val version: Int,
    val createdByMembershipId: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class MenuDayPage(val items: List<MenuDayDto>, val nextCursor: String?)
