package com.example.legion_events.data

import com.example.legion_events.model.UserProfile

interface UserRepository {
    /** Reuses the authenticated identity and creates its profile only when missing. */
    suspend fun initializeUser(): UserProfile
}

class UserInitializationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class AccountDisabledException : Exception()
