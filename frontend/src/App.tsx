import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom'
import Home from './pages/Home'
import Review from './pages/Review'
import Catalog from './pages/Catalog'
import './App.css'

function App() {
  return (
    <BrowserRouter>
      <nav className="navbar">
        <span className="navbar-brand">API Catalog</span>
        <div className="navbar-links">
          <NavLink to="/" end className={({ isActive }) => isActive ? 'active' : ''}>Home</NavLink>
          <NavLink to="/review" className={({ isActive }) => isActive ? 'active' : ''}>Submit</NavLink>
          <NavLink to="/catalog" className={({ isActive }) => isActive ? 'active' : ''}>Catalog</NavLink>
        </div>
      </nav>

      <main className="main-content">
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/review" element={<Review />} />
          <Route path="/catalog" element={<Catalog />} />
        </Routes>
      </main>
    </BrowserRouter>
  )
}

export default App
