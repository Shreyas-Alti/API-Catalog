export default function Home() {
  return (
    <div className="page">
      <h1>API Catalog</h1>
      <p className="subtitle">
        Extract, review, and browse API endpoints from source code repositories.
      </p>
      <div className="card">
        <h2>Get Started</h2>
        <p>Submit a repository URL to automatically detect its framework and extract all API endpoints.</p>
        <a href="/review" className="btn btn-primary">Submit Repository</a>
      </div>
    </div>
  )
}
