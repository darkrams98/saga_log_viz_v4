import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../../api/client'
import { MapEditor, PatternEditor, Field, RadioCards } from './editors.jsx'
import { CopyButton, ErrorBox, Loading } from '../ui'

/**
 * ویرایشگر پیکربندی نمایش (`config.json`).
 *
 * معماری‌اش یک تصمیم دارد که همه‌چیز از آن می‌آید:
 * **یک شیء JSON در حالت، و همهٔ فرم‌ها فقط نماهایی روی همان‌اند.**
 *
 * نتیجه‌اش این است که نمای «JSON خام» دقیقاً همان چیزی است که ذخیره
 * می‌شود — نه یک بازسازی تقریبی. کلیدهای توضیحی (`_راهنما…`) هم دست‌نخورده
 * باقی می‌مانند، چون هرگز از دست نمی‌روند و فقط دوباره serialize می‌شوند.
 *
 * اعتبارسنجی دو لایه است: بررسی فوری در مرورگر (regex، رنگ، عدد) برای
 * بازخورد سریع، و بررسی واقعی روی سرور با همان loaderی که در زمان اجرا
 * استفاده می‌شود — چون تنها مرجع درست، همان است.
 */

const SECTIONS = [
  { key: 'services', label: 'میکروسرویس‌ها' },
  { key: 'commands', label: 'دستورها و وضعیت‌ها' },
  { key: 'titles', label: 'عنوان‌ها و فیلدها' },
  { key: 'graph', label: 'گراف' },
  { key: 'search', label: 'جستجو' },
  { key: 'privacy', label: 'حریم خصوصی' },
  { key: 'raw', label: 'JSON خام' },
]

export default function ConfigEditor({ onSaved, prefill, onPrefillUsed }) {
  const [state, setState] = useState({ loading: true, error: null })
  const [draft, setDraft] = useState(null)
  const [original, setOriginal] = useState('')
  const [section, setSection] = useState('services')
  const [validation, setValidation] = useState(null)
  const [validating, setValidating] = useState(false)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState(null)
  const [rawText, setRawText] = useState('')
  const [rawError, setRawError] = useState(null)
  const [highlight, setHighlight] = useState(null)
  const timer = useRef(null)

  // متن نهایی — تنها چیزی که به سرور می‌رود
  const text = useMemo(
    () => (draft ? JSON.stringify(draft, null, 2) + '\n' : ''),
    [draft],
  )
  const dirty = Boolean(draft) && text !== original

  const load = useCallback(async () => {
    setState({ loading: true, error: null })
    try {
      const res = await api.admin.config()
      const parsed = JSON.parse(res.content)
      setDraft(parsed)
      setOriginal(JSON.stringify(parsed, null, 2) + '\n')
      setRawText(JSON.stringify(parsed, null, 2) + '\n')
      setValidation(res.validation)
      setState({ loading: false, error: null })
    } catch (e) {
      setState({ loading: false, error: e })
    }
  }, [])

  useEffect(() => { load() }, [load])

  // درخواست «افزودن این برچسب» از پنل برچسب‌های گم‌شده
  useEffect(() => {
    if (!prefill || !draft) return
    const map = { routingKey: 'routingKeys', commandType: 'commandTypes', title: 'titles' }[prefill.kind]
    if (!map) return
    setSection(prefill.kind === 'routingKey' ? 'services'
      : prefill.kind === 'commandType' ? 'commands' : 'titles')
    setDraft((d) => ({ ...d, [map]: { ...(d[map] || {}), [prefill.value]: prefill.value } }))
    setHighlight(prefill.value)
    onPrefillUsed?.()
  }, [prefill, draft, onPrefillUsed])

  // اعتبارسنجی سمت سرور، با تأخیر تا هر کلید فشردن یک درخواست نسازد
  useEffect(() => {
    if (!draft) return
    clearTimeout(timer.current)
    setValidating(true)
    timer.current = setTimeout(async () => {
      try {
        setValidation(await api.admin.validate(text))
      } catch { /* شبکه؛ دکمهٔ ذخیره خودش دوباره بررسی می‌کند */ }
      setValidating(false)
    }, 500)
    return () => clearTimeout(timer.current)
  }, [text, draft])

  function patch(next) {
    setDraft((d) => ({ ...d, ...next }))
    setMessage(null)
  }

  function applyRaw(value) {
    setRawText(value)
    try {
      const parsed = JSON.parse(value)
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        setRawError('ریشهٔ فایل باید یک شیء JSON باشد.')
        return
      }
      setRawError(null)
      setDraft(parsed)
    } catch (e) {
      setRawError(e.message)
    }
  }

  useEffect(() => {
    if (section !== 'raw') setRawText(text)
  }, [text, section])

  async function save() {
    setSaving(true)
    setMessage(null)
    try {
      const res = await api.admin.save(text)
      setOriginal(text)
      setValidation(res.validation)
      setMessage({
        tone: 'ok',
        text: `ذخیره شد${res.backup ? ` — پشتیبان: ${res.backup}` : ''}.`,
      })
      onSaved?.()
    } catch (e) {
      setMessage({ tone: 'error', text: e.message })
    }
    setSaving(false)
  }

  function revert() {
    try {
      setDraft(JSON.parse(original))
      setMessage(null)
    } catch { /* نباید رخ دهد */ }
  }

  if (state.loading) return <Loading rows={6} label="در حال خواندن پیکربندی…" />
  if (state.error) return <ErrorBox message={state.error.message} onRetry={load} />
  if (!draft) return null

  const errors = (validation?.issues || []).filter((i) => i.severity === 'error')
  const warnings = (validation?.issues || []).filter((i) => i.severity === 'warning')
  const blocked = errors.length > 0 || Boolean(rawError)

  return (
    <div className="config-editor">
      <nav className="section-tabs" role="tablist">
        {SECTIONS.map((s) => (
          <button key={s.key} type="button" role="tab" aria-selected={section === s.key}
                  className={`section-tab ${section === s.key ? 'active' : ''}`}
                  onClick={() => setSection(s.key)}>
            {s.label}
          </button>
        ))}
      </nav>

      <div className="section-body">
        {section === 'services' && (
          <>
            <MapEditor
              title="نام میکروسرویس‌ها"
              hint="کلید همان routingKey خام است. زنجیرهٔ ترجمه: کلید دقیق → الگو → مقدار خام."
              keyLabel="routingKey" keyPlaceholder="orchestration26.wallet.service.routing.key"
              value={draft.routingKeys} highlight={highlight}
              onChange={(v) => patch({ routingKeys: v })}
            />
            <PatternEditor value={draft.routingKeyPatterns}
                           onChange={(v) => patch({ routingKeyPatterns: v })} />
          </>
        )}

        {section === 'commands' && (
          <>
            <MapEditor
              title="انواع دستور" keyLabel="commandType" keyPlaceholder="WALLET_CHARGE"
              hint="نام دستوری که در هر مرحله اجرا می‌شود."
              value={draft.commandTypes} highlight={highlight}
              onChange={(v) => patch({ commandTypes: v })}
            />
            <MapEditor
              title="وضعیت‌ها" keyLabel="status" keyPlaceholder="ROLL_BACKED"
              hint="کلیدها بزرگ‌حروف نوشته می‌شوند."
              value={draft.statuses}
              onChange={(v) => patch({ statuses: v })}
            />
            <SeverityEditor value={draft.statusSeverity} statuses={draft.statuses}
                            onChange={(v) => patch({ statusSeverity: v })} />
          </>
        )}

        {section === 'titles' && (
          <>
            <MapEditor
              title="عنوان عملیات و مراحل" keyLabel="عنوان خام"
              keyPlaceholder="SEQ__GET_CARD_DEPOSIT_LIST"
              hint="عنوان نرمال‌شده را بنویسید (بدون مهر زمانی و شناسه)؛ همهٔ نسخه‌های مهرزمانی‌دار هم ترجمه می‌شوند."
              value={draft.titles} highlight={highlight}
              onChange={(v) => patch({ titles: v })}
            />
            <MapEditor
              title="نام فیلدها در نمای جدولی" keyLabel="مسیر فیلد"
              keyPlaceholder="commandList.rollbackDescription"
              hint="اندیس آرایه خودکار حذف می‌شود، پس یک سطر برای همهٔ مراحل کافی است."
              value={draft.fieldLabels}
              onChange={(v) => patch({ fieldLabels: v })}
            />
          </>
        )}

        {section === 'graph' && (
          <GraphEditor value={draft.graph} onChange={(v) => patch({ graph: v })} />
        )}

        {section === 'search' && (
          <SearchEditor value={draft.search} onChange={(v) => patch({ search: v })} />
        )}

        {section === 'privacy' && (
          <PrivacyEditor value={draft.privacy} onChange={(v) => patch({ privacy: v })} />
        )}

        {section === 'raw' && (
          <section className="editor-block">
            <header className="editor-head">
              <div>
                <h3>JSON خام</h3>
                <p className="editor-hint">
                  دقیقاً همان چیزی که ذخیره می‌شود. کلیدهایی که با «_» شروع می‌شوند
                  توضیح‌اند و نادیده گرفته می‌شوند.
                </p>
              </div>
              <CopyButton value={rawText} label="کپی" />
            </header>
            <textarea className="raw-editor mono" dir="ltr" spellCheck={false} rows={26}
                      value={rawText} onChange={(e) => applyRaw(e.target.value)}
                      aria-label="محتوای JSON" />
            {rawError && <p className="editor-error">JSON معتبر نیست: {rawError}</p>}
          </section>
        )}
      </div>

      <ChangeSummary original={original} next={text} />

      <IssueList errors={errors} warnings={warnings} validating={validating}
                 summary={validation?.summary} />

      {message && (
        <div className={`save-message ${message.tone}`} role="status">{message.text}</div>
      )}

      <div className="editor-actions">
        <button type="button" className="btn btn-primary" disabled={!dirty || blocked || saving}
                onClick={save}>
          {saving ? 'در حال ذخیره…' : 'ذخیره و اعمال'}
        </button>
        <button type="button" className="btn" disabled={!dirty || saving} onClick={revert}>
          بازگرداندن تغییرات
        </button>
        <span className="editor-status">
          {blocked ? 'تا رفع خطاها ذخیره ممکن نیست.'
            : dirty ? 'تغییرات ذخیره‌نشده دارید.'
              : 'همه‌چیز ذخیره شده است.'}
        </span>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------- بخش‌ها */

function SeverityEditor({ value, statuses, onChange }) {
  const known = Object.keys(statuses || {})
  const current = value || {}
  const options = [
    { value: 'success', label: 'موفق' },
    { value: 'error', label: 'خطا' },
    { value: 'unknown', label: 'نامشخص' },
  ]

  return (
    <section className="editor-block">
      <header className="editor-head">
        <div>
          <h3>رنگ وضعیت‌ها در گراف</h3>
          <p className="editor-hint">
            هر وضعیتی که اینجا تعیین نشود «نامشخص» (خاکستری) در نظر گرفته می‌شود —
            عمداً، چون حدس‌زدن موفقیت از روی وضعیت ناشناخته خطرناک است.
          </p>
        </div>
      </header>
      <div className="severity-rows">
        {known.map((key) => (
          <div className="severity-row" key={key}>
            <code dir="ltr">{key}</code>
            <span className="severity-fa">{statuses[key]}</span>
            <div className="chip-row">
              {options.map((o) => (
                <button key={o.value} type="button"
                        className={`chip ${(current[key] || 'unknown') === o.value ? 'active' : ''}`}
                        onClick={() => onChange({ ...current, [key]: o.value })}>
                  {o.label}
                </button>
              ))}
            </div>
          </div>
        ))}
        {known.length === 0 && <p className="editor-empty">اول وضعیت‌ها را تعریف کنید.</p>}
      </div>
    </section>
  )
}

function GraphEditor({ value, onChange }) {
  const g = value || {}
  const colors = g.colors || {}
  const set = (patch) => onChange({ ...g, ...patch })
  const setColor = (key, v) => set({ colors: { ...colors, [key]: v } })
  const badColor = (v) => v && !/^#[0-9a-fA-F]{3,8}$/.test(v)

  return (
    <section className="editor-block">
      <header className="editor-head">
        <div>
          <h3>گراف جریان اجرا</h3>
          <p className="editor-hint">
            اگر ساختار لاگ عوض شود و مراحل جای دیگری بروند، فقط «مسیر مراحل» را عوض کنید.
          </p>
        </div>
      </header>

      <div className="field-grid">
        <Field label="مسیر مراحل"
               hint="مسیر آرایه‌ای که هر عنصرش یک مرحله است.">
          <input className="input mono" dir="ltr" value={g.source || ''}
                 onChange={(e) => set({ source: e.target.value })} />
        </Field>
        <Field label="فیلد نام سرویس">
          <input className="input mono" dir="ltr" value={g.nodeLabelFrom || ''}
                 onChange={(e) => set({ nodeLabelFrom: e.target.value })} />
        </Field>
        <Field label="فیلد نوع دستور">
          <input className="input mono" dir="ltr" value={g.nodeSubLabelFrom || ''}
                 onChange={(e) => set({ nodeSubLabelFrom: e.target.value })} />
        </Field>
        <Field label="فیلد وضعیت">
          <input className="input mono" dir="ltr" value={g.statusFrom || ''}
                 onChange={(e) => set({ statusFrom: e.target.value })} />
        </Field>
        <Field label="برچسب شروع">
          <input className="input" value={g.startLabel || ''}
                 onChange={(e) => set({ startLabel: e.target.value })} />
        </Field>
        <Field label="برچسب پایان">
          <input className="input" value={g.endLabel || ''}
                 onChange={(e) => set({ endLabel: e.target.value })} />
        </Field>
      </div>

      <div className="color-row">
        {[
          { key: 'success', label: 'موفق' },
          { key: 'error', label: 'ناموفق' },
          { key: 'unknown', label: 'نامشخص' },
        ].map((c) => (
          <div className="color-field" key={c.key}>
            <label className="editor-label">{c.label}</label>
            <div className="color-input">
              <input type="color" value={/^#[0-9a-fA-F]{6}$/.test(colors[c.key] || '')
                ? colors[c.key] : '#888888'}
                     onChange={(e) => setColor(c.key, e.target.value)}
                     aria-label={`رنگ ${c.label}`} />
              <input className={`input mono ${badColor(colors[c.key]) ? 'has-error' : ''}`}
                     dir="ltr" value={colors[c.key] || ''}
                     onChange={(e) => setColor(c.key, e.target.value)} />
            </div>
          </div>
        ))}
      </div>

      <label className="check">
        <input type="checkbox" checked={g.showStartEnd !== false}
               onChange={(e) => set({ showStartEnd: e.target.checked })} />
        نمایش نشانگرهای «شروع» و «پایان» در گراف
      </label>
    </section>
  )
}

function SearchEditor({ value, onChange }) {
  const s = value || {}
  const fields = s.normalFields || []
  const adv = s.advanced || {}
  const setAdv = (patch) => onChange({ ...s, advanced: { ...adv, ...patch } })
  const setField = (i, patch) =>
    onChange({ ...s, normalFields: fields.map((f, x) => (x === i ? { ...f, ...patch } : f)) })

  return (
    <>
      <section className="editor-block">
        <header className="editor-head">
          <div>
            <h3>فیلدهای جستجوی عادی</h3>
            <p className="editor-hint">
              فیلد فقط وقتی قابل استفاده است که هم «فعال» باشد هم «ایندکس دارد».
              ادعای ایندکس هنگام راه‌اندازی با پایگاه داده راستی‌آزمایی می‌شود.
            </p>
          </div>
          <button type="button" className="btn btn-sm"
                  onClick={() => onChange({
                    ...s,
                    normalFields: [...fields, {
                      field: '', label: '', type: 'string', indexed: false, enabled: false,
                    }],
                  })}>
            + فیلد
          </button>
        </header>

        <div className="search-fields">
          {fields.map((f, i) => {
            const usable = f.enabled !== false && f.indexed === true
            return (
              <div className={`search-field ${usable ? 'usable' : ''}`} key={i}>
                <div className="field-grid">
                  <Field label="نام فیلد">
                    <input className="input mono" dir="ltr" value={f.field || ''}
                           onChange={(e) => setField(i, { field: e.target.value })} />
                  </Field>
                  <Field label="برچسب فارسی">
                    <input className="input" value={f.label || ''}
                           onChange={(e) => setField(i, { label: e.target.value })} />
                  </Field>
                  <Field label="نوع مقدار"
                         hint="نوع اشتباه یعنی صفر نتیجه بدون خطا.">
                    <select className="input" value={f.type || 'string'}
                            onChange={(e) => setField(i, { type: e.target.value })}>
                      <option value="auto">auto — تشخیص خودکار ObjectId</option>
                      <option value="string">string</option>
                      <option value="objectId">objectId</option>
                      <option value="number">number</option>
                    </select>
                  </Field>
                </div>
                <div className="search-field-toggles">
                  <label className="check">
                    <input type="checkbox" checked={f.indexed === true}
                           onChange={(e) => setField(i, { indexed: e.target.checked })} />
                    ایندکس در MongoDB ساخته شده
                  </label>
                  <label className="check">
                    <input type="checkbox" checked={f.enabled !== false}
                           onChange={(e) => setField(i, { enabled: e.target.checked })} />
                    در UI نمایش داده شود
                  </label>
                  <label className="check">
                    <input type="checkbox" checked={f.default === true}
                           onChange={(e) => setField(i, { default: e.target.checked })} />
                    پیش‌فرض
                  </label>
                  <span className={`field-state ${usable ? 'ok' : 'off'}`}>
                    {usable ? 'قابل استفاده' : 'غیرفعال'}
                  </span>
                  {fields.length > 1 && (
                    <button type="button" className="chip danger"
                            onClick={() => onChange({
                              ...s, normalFields: fields.filter((_, x) => x !== i),
                            })}>حذف</button>
                  )}
                </div>
                {f.enabled !== false && f.indexed !== true && (
                  <p className="editor-warning">
                    این فیلد فعال است ولی ایندکس ندارد؛ در جستجوی عادی کار نمی‌کند.
                    اول <code dir="ltr">ops/indexes.js</code> را اجرا کنید.
                  </p>
                )}
              </div>
            )
          })}
        </div>
      </section>

      <section className="editor-block">
        <header className="editor-head">
          <div>
            <h3>جستجوی پیشرفته</h3>
            <p className="editor-hint">حالت سنگین، روی فیلدهای بدون ایندکس.</p>
          </div>
        </header>

        <label className="check">
          <input type="checkbox" checked={adv.enabled !== false}
                 onChange={(e) => setAdv({ enabled: e.target.checked })} />
          جستجوی پیشرفته در دسترس باشد
        </label>

        <div className="field-grid">
          <Field label="حداکثر نتیجه" hint="بین ۱ تا ۲۰۰.">
            <input className="input" type="number" min={1} max={200}
                   value={adv.maxResults ?? 20}
                   onChange={(e) => setAdv({ maxResults: Number(e.target.value) })} />
          </Field>
          <Field label="سقف زمان (میلی‌ثانیه)"
                 hint="بیشتر از ۳۰ ثانیه یعنی یک جستجوی اشتباه نیم دقیقه به سرور فشار می‌آورد.">
            <input className="input" type="number" min={1000} max={60000} step={1000}
                   value={adv.maxTimeMs ?? 15000}
                   onChange={(e) => setAdv({ maxTimeMs: Number(e.target.value) })} />
          </Field>
        </div>

        <Field label="متن هشدار به کاربر">
          <textarea className="input" rows={3} value={adv.warning || ''}
                    onChange={(e) => setAdv({ warning: e.target.value })} />
        </Field>
      </section>
    </>
  )
}

function PrivacyEditor({ value, onChange }) {
  const profile = value?.maskingProfile || 'secretsOnly'
  return (
    <section className="editor-block">
      <header className="editor-head">
        <div>
          <h3>پوشاندن دادهٔ حساس</h3>
          <p className="editor-hint">
            این تنظیم روی همهٔ نماها اثر می‌گذارد و تغییرش در تاریخچه ثبت می‌شود.
          </p>
        </div>
      </header>

      <RadioCards
        name="maskingProfile" value={profile}
        onChange={(v) => onChange({ ...(value || {}), maskingProfile: v })}
        options={[
          {
            value: 'secretsOnly',
            label: 'فقط راز‌ها حذف شوند (پیش‌فرض)',
            description: 'رمز، OTP، توکن و CVV هرگز نمایش داده نمی‌شوند؛ '
              + 'کد ملی و شمارهٔ حساب کامل دیده می‌شوند. برای عیب‌یابی لازم است.',
          },
          {
            value: 'partial',
            label: 'ماسک جزئی',
            description: 'علاوه بر راز‌ها، کد ملی و موبایل و شمارهٔ حساب به شکل '
              + '۱۲۳****۴۵ نمایش داده می‌شوند. امن‌تر، ولی عیب‌یابی سخت‌تر.',
          },
          {
            value: 'off',
            label: 'بدون هیچ پوشاندنی',
            tone: 'danger',
            description: 'حتی رمز و توکن هم نمایش داده می‌شوند. فقط اگر کل محیط '
              + 'کاملاً قابل اعتماد است — و بدانید که این انتخاب ثبت می‌شود.',
          },
        ]}
      />

      {profile === 'off' && (
        <div className="warn-box error" role="alert">
          <span className="warn-icon" aria-hidden="true">⚠</span>
          <div>
            <strong>با این تنظیم رمز و توکن هم به مرورگر می‌رسند.</strong>
            <p>پیش از ذخیره مطمئن شوید این واقعاً چیزی است که می‌خواهید.</p>
          </div>
        </div>
      )}
    </section>
  )
}

/* --------------------------------------------------------- تفاوت‌ها */

/**
 * خلاصهٔ تغییرات — معنایی، نه سطر به سطر.
 *
 * برای یک فایل پیکربندی، «۳ کلید اضافه شد، ۱ عوض شد» بی‌نهایت مفیدتر از
 * یک diff سطری است. کسی که ۵۰ برچسب دارد نمی‌خواهد ۵۰ سطر جابه‌جا شده
 * ببیند؛ می‌خواهد بداند دقیقاً چه چیزی عوض شد.
 */
function ChangeSummary({ original, next }) {
  const changes = useMemo(() => {
    try {
      return diffObjects(JSON.parse(original), JSON.parse(next))
    } catch {
      return []
    }
  }, [original, next])

  if (changes.length === 0) return null

  return (
    <details className="change-summary" open={changes.length <= 12}>
      <summary>
        {changes.length.toLocaleString('fa-IR')} تغییر نسبت به نسخهٔ ذخیره‌شده
      </summary>
      <ul>
        {changes.slice(0, 60).map((c, i) => (
          <li key={i} className={`change change-${c.kind}`}>
            <span className="change-kind">
              {c.kind === 'added' ? 'افزوده' : c.kind === 'removed' ? 'حذف' : 'تغییر'}
            </span>
            <code dir="ltr">{c.path}</code>
            {c.kind === 'changed' && (
              <span className="change-values">
                <del>{short(c.from)}</del> ← <ins>{short(c.to)}</ins>
              </span>
            )}
            {c.kind === 'added' && <span className="change-values">{short(c.to)}</span>}
          </li>
        ))}
        {changes.length > 60 && (
          <li className="muted">و {(changes.length - 60).toLocaleString('fa-IR')} تغییر دیگر…</li>
        )}
      </ul>
    </details>
  )
}

function diffObjects(a, b, prefix = '', out = []) {
  const keys = new Set([...Object.keys(a || {}), ...Object.keys(b || {})])
  for (const key of keys) {
    const path = prefix ? `${prefix}.${key}` : key
    const left = a?.[key]
    const right = b?.[key]
    if (JSON.stringify(left) === JSON.stringify(right)) continue
    if (left === undefined) out.push({ kind: 'added', path, to: right })
    else if (right === undefined) out.push({ kind: 'removed', path, from: left })
    else if (isPlain(left) && isPlain(right)) diffObjects(left, right, path, out)
    else out.push({ kind: 'changed', path, from: left, to: right })
    if (out.length > 300) return out
  }
  return out
}

const isPlain = (v) => v && typeof v === 'object' && !Array.isArray(v)

function short(value) {
  const s = typeof value === 'string' ? value : JSON.stringify(value)
  if (!s) return ''
  return s.length > 60 ? `${s.slice(0, 59)}…` : s
}

/* ------------------------------------------------------- خطا و هشدار */

function IssueList({ errors, warnings, validating, summary }) {
  return (
    <div className="issue-list">
      <div className="issue-head">
        <span className={`issue-badge ${errors.length ? 'error' : 'ok'}`}>
          {errors.length ? `${errors.length.toLocaleString('fa-IR')} خطا` : 'بدون خطا'}
        </span>
        {warnings.length > 0 && (
          <span className="issue-badge warn">
            {warnings.length.toLocaleString('fa-IR')} هشدار
          </span>
        )}
        {validating && <span className="muted">در حال بررسی…</span>}
        {summary && (
          <span className="muted issue-summary">
            {summary.routingKeys.toLocaleString('fa-IR')} سرویس ·
            {' '}{summary.commandTypes.toLocaleString('fa-IR')} دستور ·
            {' '}{summary.titles.toLocaleString('fa-IR')} عنوان ·
            {' '}{summary.usableSearchFields.toLocaleString('fa-IR')} فیلد جستجو
          </span>
        )}
      </div>

      {[...errors, ...warnings].map((i, idx) => (
        <p key={idx} className={`issue issue-${i.severity}`}>
          {i.path && <code dir="ltr">{i.path}</code>}
          {i.message}
        </p>
      ))}
    </div>
  )
}
