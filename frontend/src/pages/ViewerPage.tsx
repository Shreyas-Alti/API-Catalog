import { useParams, useNavigate } from 'react-router-dom'
import { ApiReferenceReact } from '@scalar/api-reference-react'
import '@scalar/api-reference-react/style.css'

const BACKEND = 'http://localhost:8080'

export default function ViewerPage() {
  const { id }   = useParams<{ id: string }>()
  const navigate = useNavigate()

  if (!id) return null

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 58px)' }}>
      {/* Thin breadcrumb bar */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: '0.75rem',
        padding: '0.45rem 1.25rem', background: '#f8fafc',
        borderBottom: '1px solid #e2e8f0', flexShrink: 0,
      }}>
        <button
          onClick={() => navigate('/catalog')}
          style={{
            border: '1.5px solid #e2e8f0', background: '#fff', borderRadius: '6px',
            padding: '0.2rem 0.7rem', fontSize: '0.8rem', cursor: 'pointer', color: '#475569',
          }}>
          ← Catalog
        </button>
        <span style={{ fontSize: '0.78rem', color: '#94a3b8' }}>Repository #{id}</span>
      </div>

      {/* Scalar fills remaining height */}
      <div style={{ flex: 1, overflow: 'hidden' }}>
        <ApiReferenceReact
          configuration={{
            url: `${BACKEND}/api/repositories/${id}/openapi.json`,
            hideDownloadButton: false,
            customCss: `
              :root {
                --scalar-color-accent: #2563eb;
                --scalar-button-1: #2563eb;
                --scalar-button-1-hover: #1d4ed8;
              }
            `,
          }}
        />
      </div>
    </div>
  )
}
