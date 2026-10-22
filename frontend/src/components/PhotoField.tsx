import { useEffect, useRef, useState } from 'react'
import { Camera, SpinnerGap, X } from '@phosphor-icons/react'
import { Button } from '#/components/ui/button.tsx'
import { ApiError, uploadPhoto } from '../lib/api'

const ACCEPTED = ['image/jpeg', 'image/png', 'image/webp']
const MAX_BYTES = 5 * 1024 * 1024

export interface PhotoValue {
  photoId: string | null
  photoUrl: string | null
}

interface PhotoFieldProps {
  value: PhotoValue
  onChange: (value: PhotoValue) => void
  disabled?: boolean
}

export function PhotoField({ value, onChange, disabled }: PhotoFieldProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // An uploaded but unattached photo is not served publicly, so preview the
  // local file until the report is saved.
  const [localPreview, setLocalPreview] = useState<string | null>(null)
  const previewUrl = localPreview ?? value.photoUrl

  useEffect(() => () => {
    if (localPreview) URL.revokeObjectURL(localPreview)
  }, [localPreview])

  function clearPreview() {
    setLocalPreview((current) => {
      if (current) URL.revokeObjectURL(current)
      return null
    })
  }

  async function onFile(file: File | undefined) {
    if (!file) return
    setError(null)
    if (!ACCEPTED.includes(file.type)) {
      setError('Choose a JPEG, PNG, or WebP image.')
      return
    }
    if (file.size > MAX_BYTES) {
      setError('Photos must be 5 MB or smaller.')
      return
    }
    setUploading(true)
    try {
      const uploaded = await uploadPhoto(file)
      clearPreview()
      setLocalPreview(URL.createObjectURL(file))
      onChange({ photoId: uploaded.id, photoUrl: uploaded.url })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not upload that photo.')
    } finally {
      setUploading(false)
      if (inputRef.current) inputRef.current.value = ''
    }
  }

  return (
    <div className="grid gap-2">
      <span className="text-sm font-medium">Photo (optional)</span>
      {previewUrl ? (
        <div className="photo-preview">
          <img src={previewUrl} alt="Selected report photo" />
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={disabled || uploading}
            onClick={() => {
              clearPreview()
              onChange({ photoId: null, photoUrl: null })
            }}
          >
            <X aria-hidden="true" size={15} />
            Remove
          </Button>
        </div>
      ) : (
        <button
          type="button"
          className="photo-drop"
          disabled={disabled || uploading}
          onClick={() => inputRef.current?.click()}
        >
          {uploading ? (
            <SpinnerGap aria-hidden="true" className="animate-spin" size={22} />
          ) : (
            <Camera aria-hidden="true" size={22} />
          )}
          <span>{uploading ? 'Uploading…' : 'Add a photo'}</span>
          <span className="photo-drop-hint">JPEG, PNG, or WebP up to 5 MB</span>
        </button>
      )}
      <input
        ref={inputRef}
        type="file"
        className="sr-only"
        accept={ACCEPTED.join(',')}
        onChange={(event) => void onFile(event.target.files?.[0])}
      />
      {error && <p className="m-0 text-sm text-destructive">{error}</p>}
    </div>
  )
}
