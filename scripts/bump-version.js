const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

function getGitLog(maxCount = 20) {
    try {
        const output = execSync(`git log -n ${maxCount} --pretty=format:%s`, { encoding: 'utf8' });
        return output.split('\n').map(s => s.trim()).filter(Boolean);
    } catch (e) {
        return [];
    }
}

function determineBumpType(commits) {
    let bump = 'patch';
    for (const commit of commits) {
        // e.g. "feat!: breaking change" or "BREAKING CHANGE:" or "feat:"
        if (commit.includes('!') || commit.toLowerCase().includes('breaking change')) {
            return 'major';
        }
        if (commit.startsWith('feat') || commit.startsWith('feature')) {
            bump = 'minor';
        }
    }
    return bump;
}

function bumpVersion() {
    const versionFilePath = path.join(__dirname, '../version.json');
    const changelogPath = path.join(__dirname, '../CHANGELOG.md');

    if (!fs.existsSync(versionFilePath)) {
        console.error('Error: version.json not found!');
        process.exit(1);
    }

    const versionData = JSON.parse(fs.readFileSync(versionFilePath, 'utf8'));
    let { major, minor, patch, versionCode, versionName } = versionData;

    // Allow explicit bump type via CLI argument: node scripts/bump-version.js [major|minor|patch|custom]
    const args = process.argv.slice(2);
    let explicitBump = args[0]?.toLowerCase();

    let bumpType = explicitBump;
    if (!['major', 'minor', 'patch'].includes(bumpType)) {
        const commits = getGitLog(15);
        console.log(`Analyzing last ${commits.length} git commits for Conventional Commits bump...`);
        bumpType = determineBumpType(commits);
        console.log(`Determined automatic bump type: ${bumpType}`);
    } else {
        console.log(`Using explicit bump type: ${bumpType}`);
    }

    if (bumpType === 'major') {
        major += 1;
        minor = 0;
        patch = 0;
    } else if (bumpType === 'minor') {
        minor += 1;
        patch = 0;
    } else {
        patch += 1;
    }

    const newVersionName = `${major}.${minor}.${patch}`;
    // versionCode rule: major * 10000 + minor * 100 + patch (or simple increment)
    const newVersionCode = major * 10000 + minor * 100 + patch;

    versionData.major = major;
    versionData.minor = minor;
    versionData.patch = patch;
    versionData.versionName = newVersionName;
    versionData.versionCode = newVersionCode;

    // Also update flat fields if present
    versionData.version = newVersionName;

    fs.writeFileSync(versionFilePath, JSON.stringify(versionData, null, 2) + '\n');
    console.log(`Updated version.json to v${newVersionName} (code: ${newVersionCode})`);

    // Update CHANGELOG.md
    const today = new Date().toISOString().split('T')[0];
    const changelogEntry = `## [${newVersionName}] - ${today}\n- Automated version bump (${bumpType} release).\n\n`;

    let changelogContent = `# Changelog\n\nAll notable changes to this project will be documented in this file.\n\n`;
    if (fs.existsSync(changelogPath)) {
        const existing = fs.readFileSync(changelogPath, 'utf8');
        // Insert new entry after header
        const headerEndRegex = /(# Changelog[\s\S]*?)(##\s+\[|\n\n)/;
        if (headerEndRegex.test(existing)) {
            changelogContent = existing.replace(headerEndRegex, `$1${changelogEntry}`);
        } else {
            changelogContent = existing + '\n\n' + changelogEntry;
        }
    } else {
        changelogContent += changelogEntry;
    }

    fs.writeFileSync(changelogPath, changelogContent);
    console.log(`Appended release notes for v${newVersionName} to CHANGELOG.md`);
}

bumpVersion();
