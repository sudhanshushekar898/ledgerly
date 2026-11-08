import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import { z } from 'zod'
import { ApiError, describeError } from './errors'

/** Mirrors the backend password policy so users get feedback before submitting. */
export const passwordSchema = z
  .string()
  .min(12, 'Use at least 12 characters')
  .refine((value) => new TextEncoder().encode(value).length <= 72, 'Use at most 72 bytes')

/**
 * Copies server-side field errors onto the form. Returns a message for the form-level alert when the problem is
 * not tied to a field.
 */
export function applyApiError<T extends FieldValues>(error: unknown, setError: UseFormSetError<T>): string | null {
  if (error instanceof ApiError && error.fieldErrors.length > 0) {
    error.fieldErrors.forEach((e) => setError(e.field as Path<T>, { message: e.message }))
    return null
  }
  return describeError(error)
}
