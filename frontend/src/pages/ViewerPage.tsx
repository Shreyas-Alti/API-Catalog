import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { ApiReferenceReact } from '@scalar/api-reference-react'
import '@scalar/api-reference-react/style.css'
import { getRepository } from '../api/client'

const BACKEND = 'http://localhost:8080'

export default function ViewerPage() {
  const { id }   = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [repoName, setRepoName] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    getRepository(Number(id))
      .then(r => setRepoName(r.name))
      .catch(() => setRepoName(null))
  }, [id])

  if (!id) return null

  return (
    <div style={{
      position: 'fixed', top: '52px', left: 0, right: 0, bottom: 0,
      display: 'flex', flexDirection: 'column', zIndex: 10,
    }}>
      {/* Breadcrumb */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: '0.75rem', flexShrink: 0,
        padding: '0.45rem 1.25rem',
        background: 'rgba(8,8,18,0.97)',
        borderBottom: '1px solid rgba(255,255,255,0.08)',
      }}>
        <button
          onClick={() => navigate('/catalog')}
          style={{
            border: '1px solid rgba(255,255,255,0.12)', background: 'rgba(255,255,255,0.05)',
            borderRadius: '6px', padding: '0.2rem 0.7rem', fontSize: '0.8rem',
            cursor: 'pointer', color: '#94a3b8',
          }}>
          ← Catalog
        </button>
        <span style={{ fontSize: '0.82rem', color: '#64748b' }}>
          {repoName ?? `Repository #${id}`}
        </span>
      </div>

      {/* Scalar */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        <ApiReferenceReact
          configuration={{
            url: `${BACKEND}/api/repositories/${id}/openapi.json`,
            darkMode: true,
            hideDownloadButton: false,
            showSidebar: true,
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
