package mihon.core.archive

import java.io.IOException

/**
 * Base exception for archive operations and reading failures.
 */
open class ArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Thrown when an archive file has an invalid, corrupt, or truncated structure.
 */
class CorruptedArchiveException(message: String, cause: Throwable? = null) : ArchiveException(message, cause)

/**
 * Thrown when an operation is attempted on an archive reader that has been closed.
 */
class ArchiveClosedException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
