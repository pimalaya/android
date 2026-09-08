---
cairn: log
change: standard-setup-prompt-titles
landed: 2026-09-08
---

# The standard screen shows switches, and its prompts say what they are for

Two things the first run of the standard setup caught, both in what the screen shows rather than in what it does.

The configurations were still drawn under every switch. Withholding the manual row was not withholding the list: the options are built in both modes, because the password sign-in the standard setup uses is picked out of them, and the section drew whatever the list held. It now draws them only in the advanced setup, which is the one that asks.

And every credential prompt was titled "Sign in". A run connecting three domains at three servers opens three of them in a row, and three identical titles read as one prompt failing twice rather than as a sequence with two steps left. The standard setup now titles a prompt with the domains it signs in for, and appends its place in the sequence whenever there is more than one step; the protocol and the authentication method stay off it, being exactly what that setup exists not to ask about. The advanced sequence keeps its own title, which names both.

Capabilities moved: onboarding.
