import { useState, useEffect } from 'react'
import { useParams } from 'react-router-dom'
import { ApiReferenceReact } from '@scalar/api-reference-react'
import '@scalar/api-reference-react/style.css'
import { getRepository } from '../api/client'
import Breadcrumbs from '../components/Breadcrumbs'

const BACKEND = 'http://localhost:8080'

export default function ViewerPage() {
  const { id } = useParams<{ id: string }>()
  const [repoName, setRepoName] = useState<string | null>(null)
  // Mirror our app's theme so Scalar re-renders in the correct mode
  const [isDark, setIsDark] = useState(
    !document.documentElement.classList.contains('light')
  )

  useEffect(() => {
    if (!id) return
    getRepository(Number(id))
      .then(r => setRepoName(r.name))
      .catch(() => setRepoName(null))
  }, [id])

  // Watch for theme class changes driven by our navbar toggle
  useEffect(() => {
    const obs = new MutationObserver(() => {
      setIsDark(!document.documentElement.classList.contains('light'))
    })
    obs.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
    return () => obs.disconnect()
  }, [])

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

      {/* Scalar — theme continuously enforced via forceDarkModeState, toggle hidden via hideDarkModeToggle */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        <ApiReferenceReact
          key={String(isDark)}
          configuration={{
            url: `${BACKEND}/api/repositories/${id}/openapi.json`,
            forceDarkModeState: isDark ? 'dark' : 'light',
            hideDarkModeToggle: true,
            hideDownloadButton: false,
            showSidebar: true,
          }}
        />
      </div>
    </div>
  )
}

