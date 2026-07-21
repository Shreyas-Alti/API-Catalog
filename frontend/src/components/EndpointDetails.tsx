import type { ApiParameter, ApiField } from '../api/client'
import { endpointLabel } from '../utils/humanReadable'

interface Props {
  // Contextual fields for the title and copy function
  method?: string
  path?: string
  handler?: string | null
  description?: string | null
  // Extracted detail fields
  parameters?: ApiParameter[] | null
  requestBodyType?: string | null
  requestBodyFields?: ApiField[] | null
  responseBodyType?: string | null
  responseBodyFields?: ApiField[] | null
  statusCodes?: number[] | null
  tags?: string[] | null
  sourceFile?: string | null
  sourceLine?: number | null
}

const LOC_COLORS: Record<string, string> = {
  PATH: '#2563eb', QUERY: '#f59e0b', BODY: '#22c55e',
  HEADER: '#8b5cf6', COOKIE: '#64748b',
}

function FieldTable({ fields }: { fields: ApiField[] }) {
  return (
    <table className="detail-table">
      <thead>
        <tr><th>Field</th><th>Type</th><th>Validations</th></tr>
      </thead>
      <tbody>
        {fields.map((f, i) => (
          <tr key={i}>
            <td className="mono">{f.name}</td>
            <td className="mono">{f.type ?? '—'}</td>
            <td className="detail-validations">{f.validations?.join(' ') ?? ''}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export function EndpointDetails({
  method, path, handler, description,
  parameters, requestBodyType, requestBodyFields,
  responseBodyType, responseBodyFields,
  statusCodes, tags, sourceFile, sourceLine,
}: Props) {
  const label = endpointLabel({ description, handler, path })
  const hasParams   = parameters && parameters.length > 0
  const hasReqBody  = requestBodyType || (requestBodyFields && requestBodyFields.length > 0)
  const hasRespBody = responseBodyType || (responseBodyFields && responseBodyFields.length > 0)
  const hasCodes    = statusCodes && statusCodes.length > 0
  const hasTags     = tags && tags.length > 0

  if (!label && !hasParams && !hasReqBody && !hasRespBody && !hasCodes && !hasTags && !sourceFile) {
    return <p className="empty-text" style={{ padding: '0.5rem 0' }}>No additional details extracted.</p>
  }

  return (
    <div className="endpoint-details">
      {label && (
        <div className="details-header">
          <span className="endpoint-human-title">{label}</span>
        </div>
      )}
      {hasParams && (
        <div className="detail-section">
          <div className="detail-section-title">Parameters</div>
          <table className="detail-table">
            <thead>
              <tr><th>Name</th><th>Type</th><th>In</th><th>Required</th><th>Validations</th></tr>
            </thead>
            <tbody>
              {parameters!.map((p, i) => (
                <tr key={i}>
                  <td className="mono">{p.name ?? '—'}</td>
                  <td className="mono">{p.type ?? '—'}</td>
                  <td>
                    <span className="loc-badge" style={{ background: LOC_COLORS[p.location] ?? '#64748b' }}>
                      {p.location}
                    </span>
                  </td>
                  <td style={{ textAlign: 'center' }}>{p.required ? '✓' : ''}</td>
                  <td className="detail-validations">{p.validations?.join(' ') ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {hasReqBody && (
        <div className="detail-section">
          <div className="detail-section-title">
            Request Body{requestBodyType ? <span className="detail-type-name">{requestBodyType}</span> : ''}
          </div>
          {requestBodyFields && requestBodyFields.length > 0
            ? <FieldTable fields={requestBodyFields} />
            : <p className="empty-text">Fields not resolved.</p>}
        </div>
      )}

      {hasRespBody && (
        <div className="detail-section">
          <div className="detail-section-title">
            Response{responseBodyType ? <span className="detail-type-name">{responseBodyType}</span> : ''}
          </div>
          {responseBodyFields && responseBodyFields.length > 0
            ? <FieldTable fields={responseBodyFields} />
            : <p className="empty-text">Fields not resolved.</p>}
        </div>
      )}

      <div className="detail-footer">
        {hasCodes && (
          <div className="detail-chips">
            <span className="chips-label">Status</span>
            {statusCodes!.map(c => (
              <span key={c} className={`status-chip status-${Math.floor(c / 100)}xx`}>{c}</span>
            ))}
          </div>
        )}
        {hasTags && (
          <div className="detail-chips">
            <span className="chips-label">Tags</span>
            {tags!.map(t => <span key={t} className="tag-chip">{t}</span>)}
          </div>
        )}
        {sourceFile && (
          <span className="source-location mono">
            {sourceFile}{sourceLine ? `:${sourceLine}` : ''}
          </span>
        )}
      </div>
    </div>
  )
}
