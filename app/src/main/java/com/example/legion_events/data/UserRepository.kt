package com.example.legion_events.data

import com.example.legion_events.model.UserProfile

/** Gives controllers a way to open an account without depending on Firebase directly. */
interface UserRepository {
    /**
     * Reuses the authenticated identity and creates its profile only when missing.
     * @return The saved profile, including whether it is disabled.
     * @throws UserInitializationException If authentication or profile setup fails.
     * @throws AccountDisabledException If authentication reports a disabled account.
     */
    suspend fun initializeUser(): UserProfile
}

/**
 * Explains an account setup failure in language suitable for the screen.
 * @param message The explanation the user should see.
 * @param cause The underlying failure, if available.
 */
class UserInitializationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Signals that Firebase has disabled the authenticated account. */
class AccountDisabledException : Exception()
