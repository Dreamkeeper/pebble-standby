# Delta: suspension

## ADDED Requirements

### Requirement: Planned pause from the phone
The phone SHALL offer a pause with a horizon the wearer chooses (presets
of about 2 h and 4 h, the rest of the day, tomorrow morning, and a
clock). Starting it SHALL suspend the watch for the same period with
auto-resume on wear whenever the watch can be reached (re-sent at each
watchapp open until the watch confirms), declare an offline window of
the same length on the server, show "Paused until <time>" on Home, and
keep the phone's watch-silence and link faults quiet until the horizon.
The server and the contacts remain subject to the normal attention
rules. The pause SHALL end at the horizon, when the wearer resumes on
the phone (which also resumes the watch), or when the watch reports
that it resumed on its own (worn again or SELECT); ending early SHALL
cancel the server window.

#### Scenario: A day without the watch
- **WHEN** the wearer pauses until tomorrow 08:00 and leaves the watch
  in a locker
- **THEN** Home reads "Paused until tomorrow 08:00", no "needs
  attention" notification is raised for the silent watch, and the
  dashboard shows the wearer as paused

#### Scenario: Putting the watch back on ends the pause everywhere
- **WHEN** the suspended watch is worn again and auto-resumes before the
  horizon
- **THEN** the phone clears its pause, cancels the server window, and
  Home returns to Covered without any tap

### Requirement: "Until worn again" on the watch
The watch menu SHALL offer, after the timed presets, an auto-resuming
suspension that ends on wear evidence or SELECT and is capped at 14 h
(`CM_UNTIL_WORN_MIN`) so a forgotten watch is not silent indefinitely.

#### Scenario: Locker decision without the phone
- **WHEN** the wearer cycles UP to "Until worn again"
- **THEN** the watch shows "Until worn again", no detector fires until
  it is worn again, and after 14 h without wear the suspension expires
  and the not-worn rules apply
