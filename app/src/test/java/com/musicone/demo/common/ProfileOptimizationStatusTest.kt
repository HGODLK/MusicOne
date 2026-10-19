package com.musicone.demo

import androidx.profileinstaller.ProfileVerifier.CompilationStatus
import org.junit.Assert.*
import org.junit.Test

class ProfileOptimizationStatusTest {
    @Test fun confirmedQueuedProfileShowsNotice() {
        assertTrue(profileOptimizationNoticeVisible(CompilationStatus.RESULT_CODE_PROFILE_ENQUEUED_FOR_COMPILATION, false))
    }
    @Test fun referenceProfileHidesStaleQueuedResultAfterBackgroundCompilation() {
        assertFalse(profileOptimizationNoticeVisible(CompilationStatus.RESULT_CODE_PROFILE_ENQUEUED_FOR_COMPILATION, true))
    }
    @Test fun compiledResultsHideNotice() {
        for (code in listOf(CompilationStatus.RESULT_CODE_COMPILED_WITH_PROFILE,
            CompilationStatus.RESULT_CODE_COMPILED_WITH_PROFILE_NON_MATCHING)) {
            assertFalse(profileOptimizationNoticeVisible(code, false))
            assertFalse(profileOptimizationNoticeVisible(code, true))
        }
    }
    @Test fun missingUnsupportedAndErrorResultsDoNotClaimOptimizationIsPending() {
        for (code in listOf(CompilationStatus.RESULT_CODE_NO_PROFILE_INSTALLED,
            CompilationStatus.RESULT_CODE_ERROR_UNSUPPORTED_API_VERSION,
            CompilationStatus.RESULT_CODE_ERROR_NO_PROFILE_EMBEDDED,
            CompilationStatus.RESULT_CODE_ERROR_PACKAGE_NAME_DOES_NOT_EXIST,
            CompilationStatus.RESULT_CODE_ERROR_CACHE_FILE_EXISTS_BUT_CANNOT_BE_READ,
            CompilationStatus.RESULT_CODE_ERROR_CANT_WRITE_PROFILE_VERIFICATION_RESULT_CACHE_FILE)) {
            assertFalse(profileOptimizationNoticeVisible(code, false))
        }
    }
}
