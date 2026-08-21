package com.omnideck.sdk.capability

import com.omnideck.sdk.Sku
import kotlinx.coroutines.flow.StateFlow

/**
 * Single sign-on for the whole platform (architecture.md §13, goal G3).
 *
 * A module never parses a JWT, never sees a refresh token, and never talks to the
 * identity provider. It observes [sessionState] and asks for [accessToken] when it
 * needs one; the kernel handles PKCE, rotation, single-flight refresh and Keystore
 * sealing. That containment is why token handling can be audited in one place.
 */
interface AuthService {

    val sessionState: StateFlow<SessionState>

    /**
     * A valid access token, refreshing transparently if needed.
     * Throws [AuthException] when no session can be established.
     */
    suspend fun accessToken(): String

    /** Launches the hosted sign-in flow in a Custom Tab. */
    suspend fun signIn(): SessionState

    suspend fun signOut()

    /**
     * Re-authenticates for a high-value action (payment, data export, key rotation).
     * Returns false if the user declines or fails the challenge.
     */
    suspend fun stepUp(assurance: Assurance): Boolean

    enum class Assurance { PASSWORD, BIOMETRIC, MFA }
}

sealed interface SessionState {
    data object Unknown : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val principal: Principal) : SessionState
    data class Expired(val principal: Principal) : SessionState

    val principalOrNull: Principal?
        get() = when (this) {
            is SignedIn -> principal
            is Expired -> principal
            else -> null
        }
}

/**
 * The authenticated user as modules see them. Deliberately minimal: an opaque id,
 * a display name, tenant and role claims, and entitlements. No email, no phone —
 * a module that needs PII must request it explicitly and declare the data category.
 */
data class Principal(
    val subjectId: String,
    val displayName: String?,
    val tenantId: String?,
    val roles: Set<String> = emptySet(),
    val entitlements: Set<Sku> = emptySet(),
) {
    fun hasRole(role: String): Boolean = role in roles
    fun isEntitledTo(sku: Sku): Boolean = sku in entitlements
}

class AuthException(message: String, val recoverable: Boolean = true, cause: Throwable? = null) :
    Exception(message, cause)
