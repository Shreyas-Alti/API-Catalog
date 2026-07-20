const FRAMEWORKS = [
  { name: 'Spring Boot', lang: 'Java',      color: '#6db33f' },
  { name: 'Express',     lang: 'Node.js',   color: '#68a063' },
  { name: 'NestJS',      lang: 'Node.js',   color: '#e0234e' },
  { name: 'Fastify',     lang: 'Node.js',   color: '#111' },
  { name: 'FastAPI',     lang: 'Python',    color: '#009688' },
  { name: 'Flask',       lang: 'Python',    color: '#555' },
  { name: 'Django REST', lang: 'Python',    color: '#092e20' },
  { name: 'ASP.NET Core',lang: 'C#',        color: '#512bd4' },
  { name: 'Gin',         lang: 'Go',        color: '#00acd7' },
  { name: 'Fiber',       lang: 'Go',        color: '#00acd7' },
]

const STEPS = [
  { n: '1', title: 'Submit Repository', desc: 'Paste a public Git repo URL and an optional API host URL.' },
  { n: '2', title: 'Review & Edit',     desc: 'Framework is auto-detected. Every endpoint is extracted and editable.' },
  { n: '3', title: 'Save & Browse',     desc: 'Approve results and save. Search by path, method, or framework.' },
]

export default function Home() {
  return (
    <div className="home">
      <section className="hero">
        <div className="hero-badge">API Documentation, Automated</div>
        <h1 className="hero-title">Extract API endpoints<br />from any repository</h1>
        <p className="hero-sub">Point it at a GitHub repo. API Catalog detects the framework, walks the source code, and returns a searchable catalog of every endpoint.</p>
        <div className="hero-actions">
          <a href="/review" className="btn btn-primary btn-lg">Get Started →</a>
          <a href="/catalog" className="btn btn-outline btn-lg">Browse Catalog</a>
        </div>
      </section>

      <section className="steps-section">
        <div className="steps-grid">
          {STEPS.map(s => (
            <div key={s.n} className="step-card">
              <div className="step-number">{s.n}</div>
              <h3>{s.title}</h3>
              <p>{s.desc}</p>
            </div>
          ))}
        </div>
      </section>

      <section className="frameworks-section">
        <div className="section-label">Supported Frameworks</div>
        <div className="fw-grid">
          {FRAMEWORKS.map(f => (
            <div key={f.name} className="fw-chip">
              <span className="fw-dot" style={{ background: f.color }} />
              <span className="fw-name">{f.name}</span>
              <span className="fw-lang">{f.lang}</span>
            </div>
          ))}
        </div>
      </section>
    </div>
  )
}
