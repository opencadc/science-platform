.. _clipboard:

Copy & Paste Between Desktop Containers
=======================================

Since the different containers (e.g., CASA and terminal windows) on a Desktop
session may not be running on the same remote computer, copying and pasting
text from one container into another is not quite as simple as when using
a personal desktop machine.  To copy and paste within a Desktop session,
you need to make use of an intermediary application called the Clipboard.
To find the Clipboard, click on the arrow at the far left of the Desktop.

   .. image:: images/clipboard/1_desktop_landing.png

This brings up a menu with several different applications.  The Clipboard
is in about the middle of the list and can be opened by clicking on it.

   .. image:: images/clipboard/2_desktop_with_clipboard_menu.png

The image below shows an open Clipboard ready for use.

   .. image:: images/clipboard/3_clipboard_open.png

The Clipboard functions as an intermediary place to put text that you wish
to transfer from one container session to another.  You can copy
highlighted / selected text using Control-Shift-C, and paste it using
Control-Shift-V.

In the example here, a line of code is being copied from a python
program (being edited in a normal terminal window) into a running
interactive CASA session.  In the first step, the text is highlighted
in the terminal window.  Usually, this highlighted text directly transfers
into the Clipboard.

   .. image:: images/clipboard/4_text_into_clipboard.png

Next, highlight the text on the Clipboard, and type Control-Shift-C.
Then, click on the CASA terminal and type Control-Shift-V to paste the
text there.

   .. image:: images/clipboard/5_copy_text_to_casa.png

This same process applies to all other copy-paste needs as well, for example,
from a web browser window into a terminal.
