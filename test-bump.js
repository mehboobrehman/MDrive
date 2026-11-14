const { sync: parseCommit } = require('conventional-commits-parser');

function determineBumpType(commits) {
    let bump = 'patch';
    for (const commit of commits) {
        const parsed = parseCommit(commit);
        // Check for BREAKING CHANGE in notes OR '!' in header
        if (parsed.notes.some(n => n.title === 'BREAKING CHANGE') || commit.includes('!:')) {
            return 'major';
        }
        if (parsed.type === 'feat') {
            bump = 'minor';
        }
    }
    return bump;
}

// Test cases
const tests = [
    { commits: ['fix: bug fix'], expected: 'patch' },
    { commits: ['feat: new feature'], expected: 'minor' },
    { commits: ['feat!: breaking change'], expected: 'major' },
    { commits: ['fix: bug', 'feat: new feature'], expected: 'minor' },
    { commits: ['fix: bug', 'feat: new feature', 'feat!: breaking'], expected: 'major' },
    { commits: ['docs: update readme', 'chore: update dependency'], expected: 'patch' },
    { commits: ['feat: feature', 'feat: feature\n\nBREAKING CHANGE: api change'], expected: 'major' }
];

tests.forEach((t, i) => {
    const result = determineBumpType(t.commits);
    console.log(`Test ${i}: Expected ${t.expected}, Got ${result}, Result: ${result === t.expected ? 'PASS' : 'FAIL'}`);
});
