---
title: Code Review
description: What a reviewer of the JME team looks at, and what they leave to the build.
---

# Code Review

A review is a conversation about a change, not a gate in front of it. The build checks what a machine can check,
so a reviewer spends their attention on what it cannot.

## What the build checks

- The code compiles, and every test passes
- The formatting and the static analysis
- The licenses of new dependencies

## What the reviewer checks

- **Is it the right change?** A change that does the wrong thing well is still the wrong change.
- **Can the next person understand it?** Names, comments and the documentation beside the code.
- **What happens when it fails?** An error that is swallowed is found in production, not in review.

A reviewer who has nothing to say approves. A review that waits for a day is a change that waits for a day.
