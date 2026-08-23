package com.forgeai.studio

import java.util.UUID
import kotlin.random.Random

enum class ReferenceRole(val label: String) {
    SUBJECT("Subject / identity"),
    FACE("Face"),
    HAIR("Hair"),
    CLOTHING("Clothing / outfit"),
    POSE("Pose / composition"),
    STYLE("Style / lighting"),
    BACKGROUND("Background / environment"),
    DETAIL("Object / detail")
}

enum class CharacterViewRole(val label: String, val sensitive: Boolean = false) {
    FRONT("Front"),
    THREE_QUARTER("3/4 view"),
    LEFT_PROFILE("Left profile"),
    RIGHT_PROFILE("Right profile"),
    REAR("Rear"),
    FACE_CLOSEUP("Face close-up"),
    FULL_BODY("Full body"),
    EXPRESSION("Expression"),
    OUTFIT("Outfit"),
    ANATOMY_REFERENCE("Anatomy reference · adult only", sensitive = true),
    OTHER("Other")
}

enum class BackgroundViewRole(val label: String) {
    WIDE("Wide / establishing"),
    MEDIUM("Medium view"),
    DETAIL("Detail"),
    ALTERNATE_ANGLE("Alternate angle"),
    DAY("Day lighting"),
    NIGHT("Night lighting"),
    OTHER("Other")
}

data class StoredReference(
    val id: String = UUID.randomUUID().toString(),
    val path: String,
    val role: String,
    val label: String = "",
    val sensitive: Boolean = false
)

data class CharacterProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val notes: String = "",
    val adultAnatomyEnabled: Boolean = false,
    val portraitSeed: Long? = null,
    val fullBodySeed: Long? = null,
    val videoSeed: Long? = null,
    val lockedTraits: Set<String> = setOf("Face", "Body proportions"),
    val references: List<StoredReference> = emptyList()
) {
    fun usableReferences(includeAnatomy: Boolean, limit: Int = 10): List<StoredReference> {
        val priority = listOf(
            CharacterViewRole.FACE_CLOSEUP.name,
            CharacterViewRole.FRONT.name,
            CharacterViewRole.THREE_QUARTER.name,
            CharacterViewRole.FULL_BODY.name,
            CharacterViewRole.LEFT_PROFILE.name,
            CharacterViewRole.RIGHT_PROFILE.name,
            CharacterViewRole.REAR.name,
            CharacterViewRole.EXPRESSION.name,
            CharacterViewRole.OUTFIT.name,
            CharacterViewRole.OTHER.name,
            CharacterViewRole.ANATOMY_REFERENCE.name
        )
        return references
            .filter { includeAnatomy || !it.sensitive }
            .sortedBy { ref -> priority.indexOf(ref.role).let { if (it < 0) Int.MAX_VALUE else it } }
            .take(limit)
    }
}

data class BackgroundProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val notes: String = "",
    val imageSeed: Long? = null,
    val videoSeed: Long? = null,
    val references: List<StoredReference> = emptyList()
)

enum class PromptIntent(val label: String) {
    IMAGE("Text to image"),
    EDIT("Image edit"),
    VIDEO("Image to video"),
    CHARACTER("Character consistency"),
    BACKGROUND("Background consistency")
}

enum class PromptAction(val label: String) {
    OPTIMIZE("Optimize"),
    CINEMATIC("Cinematic"),
    IDENTITY("Preserve identity"),
    MERGE_REFERENCES("Merge references"),
    REALISTIC("More realistic"),
    SIMPLIFY("Simplify")
}

object SeedTools {
    fun randomSeed(): Long = Random.nextLong(1L, 2_147_483_646L)

    fun resolve(random: Boolean, seedText: String): Long {
        return if (random) randomSeed() else seedText.toLongOrNull()?.coerceAtLeast(1L) ?: randomSeed()
    }
}

object ForgePromptBuilder {
    fun editPrompt(
        userPrompt: String,
        explicitRoles: List<ReferenceRole>,
        character: CharacterProfile?,
        background: BackgroundProfile?,
        includeAnatomy: Boolean,
        negativePrompt: String = ""
    ): String {
        val lines = mutableListOf<String>()
        lines += userPrompt.trim()
        lines += "Treat image 1 as the base image unless the instruction explicitly says otherwise."

        explicitRoles.forEachIndexed { index, role ->
            lines += "Image ${index + 2} is a ${role.label.lowercase()} reference; use only the relevant visual information from it."
        }

        character?.let {
            val traits = it.lockedTraits.joinToString(", ").ifBlank { "facial identity and body proportions" }
            lines += "Preserve the established character '${it.name}' across the edit. Keep these traits consistent: $traits."
            if (it.notes.isNotBlank()) lines += "Character notes: ${it.notes.trim()}"
            if (includeAnatomy) {
                lines += "An adult anatomy reference may be included only to preserve non-sexual anatomical proportions; it is structural reference data and does not imply nudity in the requested output."
            }
        }

        background?.let {
            lines += "Use the saved background '${it.name}' as environment continuity reference."
            if (it.notes.isNotBlank()) lines += "Background notes: ${it.notes.trim()}"
        }

        lines += "Maintain coherent perspective, lighting, scale, anatomy, hands, shadows, texture, and camera characteristics. Do not unintentionally redesign referenced subjects."
        if (negativePrompt.isNotBlank()) lines += "Avoid: ${negativePrompt.trim()}"
        return lines.joinToString(" ")
    }

    fun referenceSummary(
        explicitRoles: List<ReferenceRole>,
        character: CharacterProfile?,
        background: BackgroundProfile?
    ): String = buildString {
        if (explicitRoles.isNotEmpty()) append("Explicit references: ").append(explicitRoles.joinToString { it.label }).append(". ")
        character?.let { append("Character profile: ${it.name}; locked traits: ${it.lockedTraits.joinToString()}. ") }
        background?.let { append("Background profile: ${it.name}. ") }
    }.trim()

    fun videoDetails(
        userDetails: String,
        character: CharacterProfile?,
        background: BackgroundProfile?,
        preserveIdentity: Boolean
    ): String {
        val parts = mutableListOf<String>()
        if (userDetails.isNotBlank()) parts += userDetails.trim()
        if (preserveIdentity) {
            parts += "Preserve the subject's identity continuously through every frame; prevent facial drift, body-shape drift, flicker, morphing, sudden wardrobe changes, or unintended character replacement."
        }
        character?.let {
            parts += "Maintain the established character '${it.name}' and keep ${it.lockedTraits.joinToString(", ")} consistent."
            if (it.notes.isNotBlank()) parts += "Character notes: ${it.notes.trim()}"
        }
        background?.let {
            parts += "Maintain continuity with the saved environment '${it.name}'."
            if (it.notes.isNotBlank()) parts += "Environment notes: ${it.notes.trim()}"
        }
        return parts.joinToString(" ")
    }
}
