import { useEffect } from 'react'
import { EndpointDetails } from './EndpointDetails'
import type { ApiParameter, ApiField } from '../api/client'

const METHOD_COLORS: Record<string, string> = {
  GET: '#22c55e', POST: '#2563eb', PUT: '#f59e0b', DELETE: '#ef4444', PATCH: '#8b5cf6',
}

interface Props {
  method?: string
  path?: string
  handler?: string | null
  description?: string | null
  parameters?: ApiParameter[] | null
  requestBodyType?: string | null
  requestBodyFields?: ApiField[] | null
  responseBodyType?: string | null
  responseBodyFields?: ApiField[] | null
  statusCodes?: number[] | null
  tags?: string[] | null
  sourceFile?: string | null
  sourceLine?: number | null
  /** Optional badge shown in the header (e.g. repo name) */
  badge?: string
  loading?: boolean
  onClose: () => void
}

export function EndpointModal({
  method, path, handler, description,
  parameters, requestBodyType, requestBodyFields,
  responseBodyType, responseBodyFields,
  statusCodes, tags, sourceFile, sourceLine,
  badge, loading, onClose,
}: Props) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = ''
    }
  }, [onClose])

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        {/* ── Header ──────────────────────────────── */}
        <div className="modal-header">
          {method && (
            <span className="method-badge" style={{ background: METHOD_COLORS[method] ?? '#64748b', flexShrink: 0 }}>
              {method}
            </span>
          )}
          <span className="modal-path">{path}</span>
          {badge && <span className="badge badge-green" style={{ flexShrink: 0 }}>{badge}</span>}
          <button className="modal-close" onClick={onClose} title="Close (Esc)">✕</button>
        </div>

        {/* ── Body ────────────────────────────────── */}
        <div className="modal-body">
          {loading ? (
            <p className="loading-text" style={{ padding: '1.25rem' }}>Loading details…</p>
          ) : (
            <EndpointDetails
              method={method} path={path}
              handler={handler} description={description}
              parameters={parameters}
              requestBodyType={requestBodyType} requestBodyFields={requestBodyFields}
              responseBodyType={responseBodyType} responseBodyFields={responseBodyFields}
              statusCodes={statusCodes} tags={tags}
              sourceFile={sourceFile} sourceLine={sourceLine}
            />
          )}
        </div>
      </div>
    </div>
  )
}
