export interface FieldError {
  field: string
  message: string
}

/** An RFC 7807 problem+json response from the API, or a network failure (status 0). */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: FieldError[]
  /** Echo of the X-Request-Id the backend logged, so support can find the request. */
  readonly requestId?: string

  constructor(init: { status: number; code: string; detail: string; fieldErrors?: FieldError[]; requestId?: string }) {
    super(init.detail)
    this.name = 'ApiError'
    this.status = init.status
    this.code = init.code
    this.fieldErrors = init.fieldErrors ?? []
    this.requestId = init.requestId
  }
}

export async function toApiError(response: Response): Promise<ApiError> {
  let body: Record<string, unknown> = {}
  try {
    body = (await response.json()) as Record<string, unknown>
  } catch {
    // non-JSON error (for example a proxy page): fall through to generic values
  }
  return new ApiError({
    status: response.status,
    code: typeof body.code === 'string' ? body.code : 'UNKNOWN_ERROR',
    detail: typeof body.detail === 'string' ? body.detail : 'Something went wrong. Please try again.',
    fieldErrors: Array.isArray(body.errors) ? (body.errors as FieldError[]) : [],
    requestId: typeof body.requestId === 'string' ? body.requestId : undefined,
  })
}

export function networkError(): ApiError {
  return new ApiError({ status: 0, code: 'NETWORK_ERROR', detail: 'Cannot reach the server. Check your connection and retry.' })
}

/** A message safe to show for an unexpected failure, with the request reference for support when there is one. */
export function describeError(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Something went wrong. Please try again.'
  if (error.status >= 500) {
    return `Something went wrong on our side. Please try again.${error.requestId ? ` (Reference ${error.requestId})` : ''}`
  }
  return error.message
}
