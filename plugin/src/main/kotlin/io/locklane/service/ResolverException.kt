package io.locklane.service

class ResolverException(
    message: String,
    val exitCode: Int = -1,
    val stderr: String = "",
) : RuntimeException(message)
