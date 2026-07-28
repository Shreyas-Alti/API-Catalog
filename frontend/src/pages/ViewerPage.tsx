import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { ApiReferenceReact } from '@scalar/api-reference-react'
import '@scalar/api-reference-react/style.css'
import { getRepository } from '../api/client'
import Breadcrumbs from '../components/Breadcrumbs'

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
      {/* Breadcrumb bar */}
      <div style={{
        flexShrink: 0, padding: '0.4rem 1.25rem',
        background: 'var(--bg)', borderBottom: '1px solid var(--border)',
      }}>
        <Breadcrumbs repoName={repoName ?? `Repository #${id}`} />
      </div>

      {/* Scalar — uses its own default theme which already matches our dark palette */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        <ApiReferenceReact
          configuration={{
            url: `${BACKEND}/api/repositories/${id}/openapi.json`,
            darkMode: true,
            hideDownloadButton: false,
            showSidebar: true,
          }}
        />
      </div>
    </div>
  )
}

