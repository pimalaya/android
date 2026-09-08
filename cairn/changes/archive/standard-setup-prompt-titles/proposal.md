---
cairn: change
id: standard-setup-prompt-titles
status: landed
created: 2026-09-08
---

# Take the options off the standard screen, and name the prompts it opens

## Why

Two things the standard setup got wrong on first use.

The screen still listed the configurations under every switch. The setup picks one itself and shows the switches, and the list under them was the advanced screen showing through: the options are built either way, because the password sign-in is chosen from them, and only the manual row was being withheld.

And its credential prompts were all titled "Sign in". Three domains at three servers is three prompts in a row under one title, which reads as one prompt that keeps failing rather than as a sequence making progress.

## What

**The standard screen shows switches.** The configurations are built and never drawn; the advanced setup draws them.

**A prompt says what it is for.** The standard setup titles its prompt with the domains it signs in for, and with how far along the sequence is when there is more than one step. The protocol and the authentication method stay off it, being what that setup exists not to ask about; the advanced setup keeps naming both.
