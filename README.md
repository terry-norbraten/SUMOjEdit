# SUMOjEdit PLUGIN

![screenshot](https://github.com/ontologyportal/SUMOjEdit/raw/master/screenshot.jpeg)
![screenshot](https://github.com/ontologyportal/SUMOjEdit/raw/master/screenshot-tp.jpeg)

Started with the QuickNotepad tutorial and adapting bit by bit as a syntax checker for [SUMO](https://www.ontologyportal.org)

This depends on proper installation/building of [SigmaKEE](https://github.com/ontologyportal/sigmakee).

Installation-*nix
=============================
- You are free to use OpenJDK. Latest is JDK23
- install jEdit.  On Ubuntu this is "sudo apt-get install jedit"
- Install SigmaKEE as per the [README](https://github.com/ontologyportal/sigmakee/blob/master/README.md)
- clone SUMOjEdit into your workspace directory\
  git clone https://github.com/ontologyportal/SUMOjEdit.git
- edit build.xml to conform to your paths
- add to your .bashrc\
  export JEDIT_HOME=/home/myname/.jedit editing "myname" to conform to your machine home\
  This can also be performed by executing: ant append.bashrc on the command line\
  in the SUMOjEdit directory
- From the command line in the SUMOjEdit directory, execute: source ~/.bashrc,\
  then then execute: ant
- Next, build the KB with this command from the sigmakee directory:
```sh
java -Xmx10g -Xss1m -cp $SIGMA_CP com.articulate.sigma.KB -l -R
```
- If you want to monitor the jEdit's condition and if it started successfully you can run:\
```sh
tail -f $JEDIT_HOME/activity.log
```
- Next, to start jEdit with this plugin, execute:
```sh
java -Xmx10g -Xss1m -jar /usr/share/jedit/jedit.jar
```
- Can also create a .bashrc alias for the above java command\
  alias jedit="java -Xmx10g -Xss1m -jar /usr/share/jedit/jedit.jar"

Installation-Mac
=============================
- You are free to use OpenJDK. Latest is JDK23
- install [jEdit](http://jedit.org/index.php?page=download&platform=mac)
  (Choose the Mac OS X package link)\
  After installing, you may need to go to System->Security&Privacy->Security and allow the jedit app
- Install SigmaKEE as per the [README](https://github.com/ontologyportal/sigmakee/blob/master/README.md)
- clone SUMOjEdit into your workspace directory\
  git clone https://github.com/ontologyportal/SUMOjEdit.git
- edit build.xml to conform to your paths - note that your jEdit config\
  directory on a mac is /Users/myname/Library/jEdit
- add to your \~/.zshrc\
  export JEDIT_HOME=\~/Library/jEdit\
  This can also be performed by executing: ant append.zshrc on the command line\
  in the SUMOjEdit directory
- From the command line in the SUMOjEdit directory, execute: source ~/.zshrc,\
  then then execute: ant
- Next, build the KB with this command from the sigmakee directory:
```sh
java -Xmx10g -Xss1m -cp $SIGMA_CP com.articulate.sigma.KB -l -R
```
- If you want to monitor the jEdit's condition and if it started successfully you can run:\
```sh
tail -f $JEDIT_HOME/activity.log
```
- Next, to start jEdit with this plugin, execute:
```sh
java -Xmx10g -Xss1m -jar /Applications/jEdit.app/Contents/Java/jedit.jar
```
- Can also create a .zshrc alias to perfrom the above java command\
  alias jedit="java -Xmx10g -Xss1m -jar /Applications/jEdit.app/Contents/Java/jedit.jar"

To build/run/debug/test on macOS using the NetBeans IDE
=======================================================
Define a nbproject/private/private.properties file with these keys:

\# private properties\
javaapis.dir=${user.home}/javaapis\
workspace=${javaapis.dir}/INSAFE

\# The default installation space is: ~/workspace. However, it can be anywhere on\
\# your system as long as you define the "workspace" key above.

catalina.home=${path.to.your.tomcat9}

jedit.home=${user.home}/Library/jEdit\
jedit.jar=/Applications/jEdit.app/Contents/Java/jedit.jar

\# JavaMail properties\
user=${your.email.user.name}\
my.email=${user}@${your.email.domain}\
my.name=${your.name}


Installation Issues
=============================
If you don't get syntax highlighting and the SUMO plugin menu, the following
may help.

To get the syntax highlighting, add the mode through the gui menu via Utilities->Global Options->Editing and the Edit Modes tab

Use the Add Mode tab to

- Name a mode SUMO
- With Select Mode file to be $JEDIT_HOME/modes/kif.xml
- And File Name Glob *.kif

Execution
=============================
- startup jEdit (and wait a while since it loads all the kif files specified in your Sigma config.xml,
  all of WordNet, VerbNet etc)
- load a .kif file
- go to Plugins->SUMOjEdit Plugin->check KIF syntax errors
- you can also highlight a SUO-KIF expression and Plugins->SUMOjEdit Plugin->query on highlighted expression.\
  If you have Vampire installed and it finds a proof, a new buffer will be opened to display the proof.\
  It should work with Eprover too, but needs testing.
- other functions are "format axioms" which will reformat a highlighted axiom with standard SUMO indentation.\
  "go to definition" will make a guess at where the definition for a selected term starts and put the cursor \
  at that point in the file.\
  "Browse term in Sigma" will open the public Sigma site in your browser opened on the selected term.

Customization
=============================
- you may wish to customize by right clicking in the editor and selecting "Customize This Menu,"\
select the "Context Menu" under jedit, then the '+' symbol. First, add a seperator, then select\
"Plugin: SUMOjEdit Plugin" from the Command or macro: dialog menu and add "format axioms",\
"go to definition" and "query on highlighted expressions", which are handy to have on the context\
menu as well as the main plugin menu. In later versions this may already be performed as part of\
the configuration, but for now you'll need to add the menu items manually.
- install the EditorScheme plugin with Plugins->PluginManager->Install-EditorScheme then\
Plugins-EditorScheme->SchemeSelector. I like the Neon theme as the best "Dark mode" option
- If you have a small screen or imperfect eyes you may wish to adjust the font size with\
Utilities->GlobalOptions->jEdit->TextArea->TextFont
