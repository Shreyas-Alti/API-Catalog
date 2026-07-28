import { BrowserRouter, Routes, Route, NavLink, Link, useNavigate } from 'react-router-dom'
import { useState, useEffect, useRef } from 'react'
import Catalog from './pages/Catalog'
import Review from './pages/Review'
import ViewerPage from './pages/ViewerPage'
import { getRepositories } from './api/client'
import type { RepositorySummary } from './api/client'
import './App.css'

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
  return (
    <BrowserRouter>
      <nav className="navbar">
        <NavLink to="/" className="navbar-brand" end>API Catalog</NavLink>
        <div className="navbar-links">
          <ReposDropdown />
        </div>
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
