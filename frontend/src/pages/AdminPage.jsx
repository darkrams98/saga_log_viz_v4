import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { adminToken, api } from '../api/client'
import ConfigEditor from '../components/admin/ConfigEditor.jsx'
import { BaseConfigPanel, HistoryPanel, StatusPanel, UsagePanel } from '../components/admin/panels.jsx'
import { EmptyState } from '../components/ui'

/**
 * صفحهٔ مدیریتی.
 *
 * دو نکتهٔ طراحی که ارزش گفتن دارند:
 *
 *   ۱) **جدا از صفحهٔ اصلی است، نه یک تب کنارش.** پشتیبان روزانه هرگز
 *      نباید سهواً وارد جایی شود که می‌تواند پوشاندن دادهٔ حساس را خاموش کند.
 *
 *   ۲) **توکن در sessionStorage می‌ماند، نه localStorage.** با بستن تب
 *      پاک می‌شود — روی ایستگاه کاری مشترک این تفاوت مهم است.
 *
 * مسیرها: `/admin/config` (پیش‌فرض)، `/admin/status`، `/admin/usage`،
 * `/admin/history`، `/admin/base`.
 */

const TABS = [
  { key: 'config', label: 'پیکربندی نمایش' },
  { key: 'usage', label: 'آمار و برچسب‌های گم‌شده' },
  { key: 'status', label: 'سلامت سرویس' },
  { key: 'history', label: 'تاریخچه و نسخه‌ها' },
  { key: 'base', label: 'تنظیمات زیرساختی' },
]

export default function AdminPage({ adminEnabled }) {
  const { tab } = useParams()
  const navigate = useNavigate()
  const active = TABS.some((t) => t.key === tab) ? tab : 'config'

  const [token, setToken] = useState(() => adminToken.get())
  const [checking, setChecking] = useState(Boolean(adminToken.get()))
  const [authed, setAuthed] = useState(false)
  const [error, setError] = useState(null)
  const [prefill, setPrefill] = useState(null)
  const [reloadKey, setReloadKey] = useState(0)

  const verify = useCallback(async () => {
    setChecking(true)
    setError(null)
    try {
      await api.admin.status()
      setAuthed(true)
    } catch (e) {
      setAuthed(false)
      setError(e)
      if (e.status === 401) adminToken.clear()
    }
    setChecking(false)
  }, [])

  useEffect(() => {
    if (adminToken.get()) verify()
  }, [verify])

  function submit(e) {
    e.preventDefault()
    adminToken.set(token.trim())
    verify()
  }

  function signOut() {
    adminToken.clear()
    setToken('')
    setAuthed(false)
  }

  if (adminEnabled === false) {
    return (
      <div className="admin-shell">
        <EmptyState
          icon="🔒"
          title="API مدیریتی غیرفعال است"
          text="متغیر محیطی ADMIN_TOKEN روی سرور تنظیم نشده است. تا وقتی تنظیم نشود،
                این صفحه عمداً کار نمی‌کند — فراموش‌کردن پیکربندی نباید یعنی درِ باز."
        />
        <p className="table-note" style={{ textAlign: 'center' }}>
          راهنما: <code dir="ltr">docs/07-deployment.md</code>
        </p>
      </div>
    )
  }

  if (!authed) {
    return (
      <div className="admin-shell">
        <form className="token-gate" onSubmit={submit}>
          <h1>ورود به بخش مدیریت</h1>
          <p className="viewer-sub">
            توکن مدیریتی را وارد کنید. توکن فقط تا بسته‌شدن این تب نگه داشته می‌شود.
          </p>
          <input
            className="input mono" dir="ltr" type="password" autoFocus
            value={token} onChange={(e) => setToken(e.target.value)}
            placeholder="ADMIN_TOKEN" aria-label="توکن مدیریتی"
          />
          <button type="submit" className="btn btn-primary" disabled={checking || !token.trim()}>
            {checking ? 'در حال بررسی…' : 'ورود'}
          </button>

          {error && (
            <div className="warn-box error" role="alert">
              <span className="warn-icon" aria-hidden="true">⚠</span>
              <div>
                <strong>{error.message}</strong>
                {error.hint && <p>{error.hint}</p>}
              </div>
            </div>
          )}

          <Link className="linkish" to="/log">بازگشت به نمایش لاگ</Link>
        </form>
      </div>
    )
  }

  return (
    <div className="admin-shell">
      <header className="admin-head">
        <div>
          <h1>مدیریت سرویس</h1>
          <p className="viewer-sub">
            تغییرات پیکربندی بلافاصله اعمال می‌شوند و در تاریخچه ثبت می‌مانند.
          </p>
        </div>
        <div className="chip-row">
          <button type="button" className="chip" onClick={async () => {
            await api.admin.reload()
            setReloadKey((k) => k + 1)
          }}>
            بازخوانی از دیسک
          </button>
          <Link className="chip" to="/log">نمایش لاگ</Link>
          <button type="button" className="chip danger" onClick={signOut}>خروج</button>
        </div>
      </header>

      <nav className="admin-tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.key} type="button" role="tab" aria-selected={active === t.key}
                  className={`admin-tab ${active === t.key ? 'active' : ''}`}
                  onClick={() => navigate(`/admin/${t.key}`)}>
            {t.label}
          </button>
        ))}
      </nav>

      <div className="admin-content">
        {active === 'config' && (
          <ConfigEditor
            key={reloadKey}
            prefill={prefill}
            onPrefillUsed={() => setPrefill(null)}
            onSaved={() => setReloadKey((k) => k)}
          />
        )}
        {active === 'usage' && (
          <UsagePanel onAddLabel={(item) => {
            setPrefill(item)
            navigate('/admin/config')
          }} />
        )}
        {active === 'status' && <StatusPanel key={reloadKey} />}
        {active === 'history' && (
          <HistoryPanel onRestored={() => setReloadKey((k) => k + 1)} />
        )}
        {active === 'base' && <BaseConfigPanel key={reloadKey} />}
      </div>
    </div>
  )
}
