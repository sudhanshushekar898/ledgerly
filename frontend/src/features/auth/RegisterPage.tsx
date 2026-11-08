import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { Alert, Button, TextField } from '../../components/ui'
import { ApiError } from '../../lib/errors'
import { applyApiError, passwordSchema } from '../../lib/forms'
import { AuthLayout } from './AuthLayout'
import { useAuth } from './useAuth'

const schema = z.object({
  fullName: z.string().trim().min(1, 'Enter your name').max(100, 'Use at most 100 characters'),
  email: z.string().min(1, 'Enter your email').email('Enter a valid email'),
  password: passwordSchema,
})
type Values = z.infer<typeof schema>

export function RegisterPage() {
  const { register: signUp } = useAuth()
  const navigate = useNavigate()
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<Values>({ resolver: zodResolver(schema) })

  const onSubmit = handleSubmit(async ({ fullName, email, password }) => {
    setFormError(null)
    try {
      await signUp(email.trim(), password, fullName.trim())
      navigate('/', { replace: true })
    } catch (error) {
      if (error instanceof ApiError && error.code === 'EMAIL_TAKEN') {
        setError('email', { message: 'An account with this email already exists. Sign in instead.' })
      } else if (error instanceof ApiError && error.code === 'VALIDATION_FAILED' && error.fieldErrors.length === 0) {
        // The server's password policy also rejects common and very simple passwords.
        setError('password', { message: error.message })
      } else {
        setFormError(applyApiError(error, setError))
      }
    }
  })

  return (
    <AuthLayout title="Create your wallet" subtitle="It takes a minute. Your INR wallet opens instantly.">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {formError && <Alert>{formError}</Alert>}
        <TextField label="Full name" autoComplete="name" error={errors.fullName?.message} {...register('fullName')} />
        <TextField label="Email" type="email" autoComplete="email" error={errors.email?.message} {...register('email')} />
        <TextField
          label="Password"
          type="password"
          autoComplete="new-password"
          hint="At least 12 characters. A short sentence works well."
          error={errors.password?.message}
          {...register('password')}
        />
        <Button type="submit" loading={isSubmitting} className="w-full">
          Create wallet
        </Button>
        <p className="text-center text-sm text-slate-400">
          Already have one?{' '}
          <Link to="/login" className="font-medium text-teal-300 hover:underline">
            Sign in
          </Link>
        </p>
      </form>
    </AuthLayout>
  )
}
