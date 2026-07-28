import { useState, useEffect } from 'react'
import { useParams } from 'react-router-dom'
import { ApiReferenceReact } from '@scalar/api-reference-react'
import '@scalar/api-reference-react/style.css'
import { getRepository } from '../api/client'
import Breadcrumbs from '../components/Breadcrumbs'

const BACKEND = 'http://localhost:8080'

// Hide Scalar's built-in dark/light toggle — our navbar toggle handles theme globally.
// Targets the known class name plus every plausible fallback selector.
const HIDE_SCALAR_TOGGLE = `
  .dark-light-toggle,
  .scalar-button.dark-light-toggle,
  .references-navigation .dark-light-toggle,
  button[title="Toggle dark mode"],
  button[title="Toggle light mode"],
  button[title="Switch to dark mode"],
  button[title="Switch to light mode"],
  button[aria-label="Toggle dark mode"],
  button[aria-label="Toggle light mode"],
  button[aria-label="Switch to dark mode"],
  button[aria-label="Switch to light mode"],
  [data-testid="dark-mode-toggle"],
  [class*="DarkLight"],
  [class*="ThemeToggle"],
  [class*="ColorMode"] { display: none !important; }
`

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

      {/* Scalar — remounted on theme change (key) so darkMode prop is always applied fresh */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        <ApiReferenceReact
          key={String(isDark)}
          configuration={{
            url: `${BACKEND}/api/repositories/${id}/openapi.json`,
            darkMode: isDark,
            hideDownloadButton: false,
            showSidebar: true,
            customCss: HIDE_SCALAR_TOGGLE,
          }}
        />
      </div>
    </div>
  )
}

