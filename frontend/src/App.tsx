import { BrowserRouter, Routes, Route, NavLink, Link } from 'react-router-dom'
import { useState, useEffect, useRef } from 'react'
import Catalog from './pages/Catalog'
import Review from './pages/Review'
import ViewerPage from './pages/ViewerPage'
import { getRepositories } from './api/client'
import type { RepositorySummary } from './api/client'
import './App.css'

// ── Theme toggle ─────────────────────────────────────────────────────────────
function useTheme() {
  const [dark, setDark] = useState<boolean>(() => {
    const saved = localStorage.getItem('theme')
    if (saved) return saved === 'dark'
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  })

  useEffect(() => {
    document.documentElement.classList.toggle('light', !dark)
    localStorage.setItem('theme', dark ? 'dark' : 'light')
    // Notify same-tab listeners (storage events only fire cross-tab by default)
    window.dispatchEvent(new StorageEvent('storage', { key: 'theme', newValue: dark ? 'dark' : 'light' }))
  }, [dark])

  return { dark, toggle: () => setDark(d => !d) }
}

function ThemeToggle({ dark, toggle }: { dark: boolean; toggle: () => void }) {
  return (
    <button
      className="theme-toggle"
      onClick={toggle}
      title={dark ? 'Switch to light mode' : 'Switch to dark mode'}
      aria-label={dark ? 'Switch to light mode' : 'Switch to dark mode'}
    >
      {dark ? (
        // Sun — switch to light
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="4" />
          <line x1="12" y1="2"  x2="12" y2="5"  />
          <line x1="12" y1="19" x2="12" y2="22" />
          <line x1="2"  y1="12" x2="5"  y2="12" />
          <line x1="19" y1="12" x2="22" y2="12" />
          <line x1="4.22"  y1="4.22"  x2="6.34"  y2="6.34"  />
          <line x1="17.66" y1="17.66" x2="19.78" y2="19.78" />
          <line x1="19.78" y1="4.22"  x2="17.66" y2="6.34"  />
          <line x1="6.34"  y1="17.66" x2="4.22"  y2="19.78" />
        </svg>
      ) : (
        // Moon — switch to dark
        <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" stroke="none">
          <path d="M21 12.79A9 9 0 1 1 11.21 3a7 7 0 0 0 9.79 9.79z" />
        </svg>
      )}
    </button>
  )
}

function ReposDropdown() {
  const [open, setOpen] = useState(false)
  const [repos, setRepos] = useState<RepositorySummary[]>([])
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    getRepositories().then(setRepos).catch(() => {})
  }, [])

  // Close on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  return (
    <div className="dropdown" ref={ref}>
      <button className="dropdown-trigger" onClick={() => setOpen(o => !o)}>
        Repositories &#9662;
      </button>
      <div className={`dropdown-menu${open ? ' open' : ''}`}>
        {repos.length === 0
          ? <span className="dropdown-empty">No repositories yet</span>
          : repos.map(r => (
              <Link key={r.id} to={`/repositories/${r.id}`} onClick={() => setOpen(false)}>
                {r.name}
              </Link>
            ))
        }
      </div>
    </div>
  )
}

function App() {
  const { dark, toggle } = useTheme()

  return (
    <BrowserRouter>
      <nav className="navbar">
        <NavLink to="/" className="navbar-brand" end>API Catalog</NavLink>
        <div className="navbar-links">
          <NavLink to="/" end className={({ isActive }) => isActive ? 'nav-link nav-link--active' : 'nav-link'}>
            Home
          </NavLink>
          <ReposDropdown />
        </div>
        <ThemeToggle dark={dark} toggle={toggle} />
      </nav>

      <main className="main-content">
        <Routes>
          <Route path="/" element={<Catalog />} />
          <Route path="/review" element={<Review />} />
          <Route path="/repositories/:id" element={<ViewerPage />} />
          {/* Legacy aliases */}
          <Route path="/viewer/:id" element={<ViewerPage />} />
          <Route path="/catalog" element={<Catalog />} />
        </Routes>
      </main>
    </BrowserRouter>
  )
}

export default App
