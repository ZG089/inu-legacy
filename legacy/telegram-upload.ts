import { BotKeyboard, html, InputMedia, MemoryStorage, TelegramClient } from '@mtcute/node'
import { execSync } from 'node:child_process'
import fs from 'node:fs/promises'
import { basename, join, resolve } from 'node:path'

async function findApks(searchDirs: string[]): Promise<string[]> {
  for (const dir of searchDirs) {
    try {
      const stat = await fs.stat(dir)
      if (!stat.isDirectory()) continue
      const entries = await fs.readdir(dir, { recursive: true })
      const apks = entries
        .filter(f => f.endsWith('.apk'))
        .map(f => join(dir, f))
      if (apks.length === 0) continue

      // If signed apks exist, prefer them
      const signed = apks.filter(f => f.includes('signed'))
      return signed.length > 0 ? signed : apks
    } catch {
      // dir doesn't exist, try next
    }
  }
  return []
}

function getCommits(baseSha: string, headSha: string, maxCommits = 25, maxSubject = 80): { lines: string[], total: number } {
  let logOutput = ''
  try {
    if (baseSha && /^[0-9a-f]{40}$/.test(baseSha)) {
      logOutput = execSync(`git log --no-merges --abbrev=8 --format='%h%x09%s' ${baseSha}..${headSha}`, { encoding: 'utf8' }).trim()
    }
  } catch {
    // fallback
  }

  if (!logOutput) {
    try {
      logOutput = execSync(`git log -n 1 --abbrev=8 --format='%h%x09%s' ${headSha}`, { encoding: 'utf8' }).trim()
    } catch {
      logOutput = `${headSha.slice(0, 8)}\tcommit ${headSha.slice(0, 8)}`
    }
  }

  const rawLines = logOutput.split('\n').filter(Boolean)
  const total = rawLines.length
  const lines: string[] = []

  for (const line of rawLines.slice(0, maxCommits)) {
    const [h, ...rest] = line.split('\t')
    let s = rest.join('\t')
    if (s.length > maxSubject) {
      s = s.slice(0, maxSubject - 1) + '…'
    }
    lines.push(`- ${h} • ${html.escape(s)}`)
  }

  if (total > maxCommits) {
    lines.push(`- … and ${total - maxCommits} more`)
  }

  return { lines, total }
}

async function main() {
  const botToken = process.env.TELEGRAM_BOT_TOKEN
  const rawChatId = process.env.TELEGRAM_CHAT_ID || process.env.TELEGRAM_CHANNEL || '-1004364828439'
  if (!botToken) {
    console.log('TELEGRAM_BOT_TOKEN is not set, skipping Telegram notification')
    return
  }

  const repo = process.env.GITHUB_REPOSITORY || 'ZG089/inu-legacy'
  const sha = process.env.GITHUB_SHA || execSync('git rev-parse HEAD', { encoding: 'utf8' }).trim()
  const runId = process.env.GITHUB_RUN_ID || '0'
  const runNumber = process.env.GITHUB_RUN_NUMBER || '0'
  const serverUrl = process.env.GITHUB_SERVER_URL || 'https://github.com'
  const repoUrl = `${serverUrl}/${repo}`

  const baseSha = process.env.BASE_SHA || ''
  const releaseRequested = process.env.RELEASE_REQUESTED === 'true'
  const releaseResult = process.env.RELEASE_RESULT || 'skipped'
  const releaseTag = process.env.RELEASE_TAG || ''
  const isRelease = releaseRequested || Boolean(releaseTag)

  const isSuccess = releaseResult === 'success' || releaseResult === 'skipped'
  const state = isSuccess ? (isRelease ? '🚀' : '✨') : '❌'
  const variantTag = isRelease ? '#Release' : '#CI'

  const title = isRelease ? `Release ${releaseTag || 'Build'}` : `CI build #${runNumber}`
  const titleUrl = isRelease ? `${repoUrl}/releases/tag/${releaseTag}` : `${repoUrl}/actions/runs/${runId}`
  const sourceUrl = `${repoUrl}/tree/${sha}`

  const { lines, total } = getCommits(baseSha, sha)
  const commitsBlock = lines.join('\n')

  const text = `${state} <b><a href="${titleUrl}">${html.escape(title)}</a></b>\n\n<b>New commits (${total})</b>\n<blockquote expandable>${commitsBlock}</blockquote>\n\n${variantTag}`

  const btn1Text = isRelease ? '🏷️ Release Tag' : '🔍 View CI run'
  const btn1Url = isRelease ? titleUrl : `${repoUrl}/actions/runs/${runId}`

  const keyboard = BotKeyboard.inline([
    [
      BotKeyboard.url(btn1Text, btn1Url),
      BotKeyboard.url('📦 Source Code', sourceUrl),
    ],
  ])

  // Locate APKs
  const apkDir = process.env.APK_DIR || 'apks'
  const apks = await findApks([apkDir, 'signed', 'out', join(process.cwd(), 'apks')])
  console.log(`Found ${apks.length} APK(s) to attach:`)
  for (const a of apks) {
    const s = (await fs.stat(a)).size
    console.log(`  - ${a} (${(s / 1024 / 1024).toFixed(1)} MB)`)
  }

  if (process.env.DRY_RUN === '1') {
    console.log('\n[DRY RUN Message]:\n' + text)
    console.log('\n[Buttons]:', btn1Text, '->', btn1Url, '| Source Code ->', sourceUrl)
    return
  }

  // Parse chat ID (numeric channels/groups start with -100...)
  const chatId = /^-?\d+$/.test(rawChatId) ? Number(rawChatId) : rawChatId

  const apiId = Number(process.env.TELEGRAM_API_ID) || 2040
  const apiHash = process.env.TELEGRAM_API_HASH || 'b18441a1ff607e10a989891a5462e627'
  const cachedSession = process.env.MTPROTO_SESSION || undefined

  console.log('Connecting to Telegram via MTProto (@mtcute/node)...')
  const tg = new TelegramClient({
    apiId,
    apiHash,
    storage: new MemoryStorage(),
  })

  try {
    if (cachedSession) {
      await tg.importSession(cachedSession, true)
      await tg.connect()
    } else {
      await tg.start({ botToken })
    }

    console.log('Sending message to', chatId)
    const sentMsg = await tg.sendText(chatId, text, {
      replyMarkup: keyboard,
      disableWebPreview: true,
    })
    console.log('Message sent successfully (ID:', sentMsg.id, ')')

    // Send APK documents via MTProto
    for (const apk of apks) {
      const fileName = basename(apk)
      const absPath = resolve(apk)
      const sizeMb = ((await fs.stat(absPath)).size / 1024 / 1024).toFixed(1)
      console.log(`Uploading ${fileName} (${sizeMb} MB) via MTProto...`)

      await tg.sendMedia(
        chatId,
        InputMedia.document(`file:${absPath}`, {
          fileName,
          fileMime: 'application/vnd.android.package-archive',
        }),
        {
          replyTo: sentMsg.id,
        },
      )
      console.log(`Successfully uploaded ${fileName}`)
    }
  } catch (err: any) {
    console.error('Error during MTProto upload:', err?.message || err)
    throw err
  } finally {
    await tg.destroy()
  }
}

main().catch(err => {
  console.error('Failed to post to Telegram:', err)
  process.exit(1)
})
