package com.articulate.sigma.jedit;

/*
 * SUMOjEdit.java
 * part of the SUMOjEdit plugin for the jEdit text editor
 * Copyright (C) 2019 Infosys
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
import com.articulate.sigma.*;
import com.articulate.sigma.parsing.SuokifApp;
import com.articulate.sigma.parsing.SuokifVisitor;
import com.articulate.sigma.tp.*;
import com.articulate.sigma.trans.*;
import com.articulate.sigma.utils.*;

import com.google.common.io.Files;

import errorlist.DefaultErrorSource;
import errorlist.ErrorListPanel;
import errorlist.ErrorSource;

import java.awt.*;
import java.io.*;
import javax.swing.Box;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gjt.sp.jedit.*;
import org.gjt.sp.jedit.gui.RolloverButton;
import org.gjt.sp.jedit.io.VFSManager;
import org.gjt.sp.jedit.msg.BufferUpdate;
import org.gjt.sp.jedit.msg.EditPaneUpdate;
import org.gjt.sp.util.Log;

import tptp_parser.*;

/**
 * ***************************************************************
 *
 */
public class SUMOjEdit implements EBComponent, SUMOjEditActions, Runnable {

    public static boolean log = true;

    protected KB kb;
    protected FormulaPreprocessor fp;
    protected DefaultErrorSource errsrc;
    protected final KIF kif;

    // Not currently used, but good framework to have
    private DefaultErrorSource.DefaultError de;

    // Not currently used, but good framework to have
    private DefaultErrorSource.DefaultError dw;
    private boolean kbsInitialized;
    private View view;

    /**
     * ***************************************************************
     * Initializes this plugin and loads the KBs
     */
    public SUMOjEdit() {

        kbsInitialized = false;

        Log.log(Log.MESSAGE, SUMOjEdit.this, ": SUMOKBtoTPTPKB.rapidParsing==" + SUMOKBtoTPTPKB.rapidParsing);
        Log.log(Log.MESSAGE, SUMOjEdit.this, ": initializing");

        kif = new KIF();
        kif.filename = "";
    }

    /**
     * ***************************************************************
     * Starts the given named Runnable
     * @param r the Runnable to start
     * @param desc a short description to give the Thread instance
     */
    public void startThread(Runnable r, String desc) {

        Thread t = new Thread(r);
        t.setName(SUMOjEdit.class.getSimpleName() + ": " + desc);
        t.setDaemon(true);
        t.start();
    }

    /* Starts the KB initialization process for UI use only. Must only be
     * called when jEdit will be an active UI. Not meant for use by the main()
     */
    @Override
    @SuppressWarnings("SleepWhileInLoop")
    public void run() {

        // wait for the view to become active
        do
            try {
                view = jEdit.getActiveView();
                Thread.sleep(50L);
            } catch (InterruptedException ex) {System.err.println(ex);}
        while (view == null);
        view.getJMenuBar().getSubElements()[8].menuSelectionChanged(true); // force population of all Plugin items
        togglePluginMenus(kbsInitialized);

        // Will first initialize the KB
        SUMOtoTFAform.initOnce();
        kb = SUMOtoTFAform.kb;
        kbsInitialized = (kb != null);
        fp = SUMOtoTFAform.fp;
        Log.log(Log.MESSAGE, SUMOjEdit.this, ": kb: " + kb);

        // Initialize error resources
        errsrc = new DefaultErrorSource(getClass().getName(), this.view);
        boolean isKif = Files.getFileExtension(view.getBuffer().getPath()).equalsIgnoreCase("kif");
        boolean isTptp = Files.getFileExtension(view.getBuffer().getPath()).equalsIgnoreCase("tptp");
        if (isKif || isTptp) {
            ErrorSource.registerErrorSource(errsrc);
            if (isKif) {
                kif.filename = view.getBuffer().getPath();
                tellTheKbAboutLoadedKif();
                checkErrors();
            }
        }

        // Now, re-enable the plugin's menus
       togglePluginMenus(kbsInitialized);
       view.getJMenuBar().getSubElements()[8].menuSelectionChanged(false);
    }

    private void togglePluginMenus(boolean enabled) {

        // Disable the plugin's menus
        // Top view menu bar / Enhanced menu item / Plugins menu / SUMOjEdit plugin menu
        view.getJMenuBar().getSubElements()[8].getSubElements()[0].getSubElements()[3].getComponent().setEnabled(enabled);

        // Now, the right click context menu of the editor's text area in the case of customized SUMOjEdit actions
        view.getEditPane().getTextArea().setRightClickPopupEnabled(enabled);
    }

    /**
     * ***************************************************************
     * Adds a loaded KIF as a constituent to the KB so that all terms
     * can be recognized. If constituent already loaded, will just
     * return.
     */
    private void tellTheKbAboutLoadedKif() {

        if (kb.constituents.contains(kif.filename))
            return;
        Future<Boolean> future;
        boolean success = false;

        // make sure any new terms/formulas are recognized prior to error checking
        Callable<Boolean> r = () -> {
            boolean retVal = true;
            long start = System.currentTimeMillis();
            java.util.List<String> constituentsToAdd = new ArrayList<>();
            File newKbFile = new File(kif.filename);
            try {
                constituentsToAdd.add(newKbFile.getCanonicalPath());
            }
            catch (IOException ioe) {
                Log.log(Log.ERROR, this, ":tellTheKbAboutLoadedKif(): ", ioe);
                errsrc.addError( ErrorSource.ERROR, kif.filename, 1, 0, 0, ioe.getMessage());
                return false;
            }

            // Patterns after KBmanager.kbsFromXML()
            SimpleElement configuration = KBmanager.getMgr().readConfiguration(KButilities.SIGMA_HOME + File.separator + "KBs");
            String kbName = null, filename;
            boolean useCacheFile;
            for (SimpleElement element : configuration.getChildElements()) {
                if (element.getTagName().equals("kb")) {
                    kbName = element.getAttribute("name");
                    KBmanager.getMgr().addKB(kbName);
                    useCacheFile = KBmanager.getMgr().getPref("cache").equalsIgnoreCase("yes");
                    for (SimpleElement kbConst : element.getChildElements()) {
                        if (!kbConst.getTagName().equals("constituent"))
                            System.err.println("Error in KBmanager.kbsFromXML(): Bad tag: " + kbConst.getTagName());
                        filename = kbConst.getAttribute("filename");
                        if (!filename.startsWith((File.separator)))
                            filename = KBmanager.getMgr().getPref("kbDir") + File.separator + filename;
                        if (!StringUtil.emptyString(filename)) {
                            if (KButilities.isCacheFile(filename)) {
                                if (useCacheFile)
                                    constituentsToAdd.add(filename);
                            }
                            else
                                constituentsToAdd.add(filename);
                        }
                    }
                    KBmanager.getMgr().loadKB(kbName, constituentsToAdd);
                }
            }
            kb = KBmanager.getMgr().getKB(kbName);
            Log.log(Log.MESSAGE, this, ":tellTheKbAboutLoadedKif() took " + (System.currentTimeMillis() - start) + " m/s");
            return retVal;
        };
        future = KButilities.EXECUTOR_SERVICE.submit(r);
        try {
            success = future.get(); // waits for task completion
        } catch (InterruptedException | ExecutionException ex) {
            Log.log(Log.ERROR, this,":tellTheKbAboutLoadedKif(): " + ex.getMessage());
            errsrc.addError( ErrorSource.ERROR, kif.filename, 1, 0, 0, ex.getMessage());
//            ex.printStackTrace();
        } finally {
            if (success)
                Log.log(Log.MESSAGE, this, ":tellTheKbAboutLoadedKif() success: " + success);
            else
                Log.log(Log.ERROR, this, ":tellTheKbAboutLoadedKif() failure: " + success);
        }
    }

    /**
     * ***************************************************************
     * Props at: https://www.jedit.org/api/org/gjt/sp/jedit/msg/package-summary.html
     * @param msg the Edit Bus message to handle
     */
    @Override
    public void handleMessage(EBMessage msg) {

        if (msg instanceof BufferUpdate)
            bufferUpdate((BufferUpdate)msg);
//        if (msg instanceof EditorExiting)
//            editorExiting((EditorExiting)msg);
        if (msg instanceof EditPaneUpdate)
            editPaneUpdate((EditPaneUpdate)msg);
//        if (msg instanceof VFSUpdate)
//            vfsUpdate((VFSUpdate)msg);
//        if (msg instanceof ViewUpdate)
//            viewUpdate((ViewUpdate)msg);
    }

    /**
     * ***************************************************************
     * Handler for BufferUpdate CLOSED and LOADED events
     */
    private void bufferUpdate(BufferUpdate bu) {

        if (view == null) return;
        boolean isKif = Files.getFileExtension(view.getBuffer().getPath()).equalsIgnoreCase("kif");
        boolean isTptp = Files.getFileExtension(view.getBuffer().getPath()).equalsIgnoreCase("tptp");
        if (bu.getView() == view) {
//            if (bu.getWhat() == BufferUpdate.CLOSED)
//                System.out.println("BufferUpdate.CLOSED");
//            if (bu.getWhat() == BufferUpdate.CREATED)
//                System.out.println("BufferUpdate.CREATED");
//            if (bu.getWhat() == BufferUpdate.DIRTY_CHANGED)
//                System.out.println("BufferUpdate.DIRTY_CHANGED");
//            if (bu.getWhat() == BufferUpdate.LOADED)
//                System.out.println("BufferUpdate.LOADED");
//            if (bu.getWhat() == BufferUpdate.MARKERS_CHANGED)
//                System.out.println("BufferUpdate.MARKERS_CHANGED");
//            if (bu.getWhat() == BufferUpdate.PROPERTIES_CHANGED)
//                System.out.println("BufferUpdate.PROPERTIES_CHANGED");
            if (bu.getWhat() == BufferUpdate.SAVED) { // file saved
//                System.out.println("BufferUpdate.SAVED");
                if (isKif)
                    checkErrors();
            }
        }
    }

    /**
     * ***************************************************************
     */
//    private void editorExiting(EditorExiting ee) {
//
//        System.out.println(getClass().getName() + " exiting");
//    }

    /**
     * ***************************************************************
     */
    private void editPaneUpdate(EditPaneUpdate eu) {

        if (view == null) return;
        boolean isKif = Files.getFileExtension(view.getBuffer().getPath()).equalsIgnoreCase("kif");
        boolean isTptp = Files.getFileExtension(view.getBuffer().getPath()).equalsIgnoreCase("tptp");
//        if (eu.getWhat() == EditPaneUpdate.BUFFERSET_CHANGED)
//            System.out.println("EditPaneUpdate.BUFFERSET_CHANGED");
        if (eu.getWhat() == EditPaneUpdate.BUFFER_CHANGED) { // switching between files or panes and closing panes
//            System.out.println("EditPaneUpdate.BUFFER_CHANGED");
                if (isKif || isTptp) {
                    Runnable r = () -> {
                        togglePluginMenus(true);
                        ErrorSource.registerErrorSource(errsrc);
                        if (isKif && !kif.filename.equals(view.getBuffer().getPath())) {
                            togglePluginMenus(false);
                            kif.filename = view.getBuffer().getPath();
                            tellTheKbAboutLoadedKif(); // loads a new kif into the KB
                            togglePluginMenus(true);
                            checkErrors();
                        }
                    };
                    KButilities.EXECUTOR_SERVICE.submit(r); // spawn this to get out if the EDT
            } else {
                togglePluginMenus(false);
                clearWarnAndErr();
                unload();
            }
        }
//        if (eu.getWhat() == EditPaneUpdate.CREATED)
//            System.out.println("EditPaneUpdate.CREATED");
        if (eu.getWhat() == EditPaneUpdate.DESTROYED) { // jEdit exit
//            System.out.println("EditPaneUpdate.DESTROYED");
            unload();
        }
    }

    /** Clean up resources */
    private void unload() {

        ErrorSource.unregisterErrorSource(errsrc);
    }

    /**
     * ***************************************************************
     */
//    private void vfsUpdate(VFSUpdate vu) {
//
//        System.out.println("VFS update"); // file saved
//    }

//    private void viewUpdate(ViewUpdate vu) {
//
//        if (view == null) return;
//        if (vu.getView() == view) {
//            if (vu.getWhat() == ViewUpdate.ACTIVATED)
//                System.out.println("ViewUpdate.ACTIVATED");
//            if (vu.getWhat() == ViewUpdate.CREATED)
//                System.out.println("ViewUpdate.CREATED");
//            if (vu.getWhat() == ViewUpdate.CLOSED) // jEdit exit
//                System.out.println("ViewUpdate.CLOSED");
//            if (vu.getWhat() == ViewUpdate.EDIT_PANE_CHANGED)
//                System.out.println("ViewUpdate.EDIT_PANE_CHANGED");
//        }
//    }

    @Override
    public void setFOF() {

        System.out.println("setFOF(): translation set to TPTP");
        Log.log(Log.MESSAGE, this, ": translation set to TPTP");
        SUMOformulaToTPTPformula.lang = "fof";
        SUMOKBtoTPTPKB.lang = "fof";
    }

    @Override
    public void setTFF() {

        System.out.println("setTFF(): translation set to TFF");
        Log.log(Log.MESSAGE, this, ": translation set to TFF");
        SUMOformulaToTPTPformula.lang = "tff";
        SUMOKBtoTPTPKB.lang = "tff";
    }

    @Override
    public void chooseVamp() {

        System.out.println("chooseVamp(): prover set to Vampire");
        Log.log(Log.MESSAGE, this, ":chooseVamp(): prover set to Vampire");
        KBmanager.getMgr().prover = KBmanager.Prover.VAMPIRE;
    }

    @Override
    public void chooseE() {

        System.out.println("chooseE(): prover set to E");
        Log.log(Log.MESSAGE, this, ":chooseE(): prover set to E");
        KBmanager.getMgr().prover = KBmanager.Prover.EPROVER;
    }

    /**
     * ***************************************************************
     */
    private String queryResultString(TPTP3ProofProcessor tpp) {

        System.out.println("queryExp(): bindings: " + tpp.bindings);
        Log.log(Log.MESSAGE, this, ":queryExp(): bindings: " + tpp.bindings);
        System.out.println("queryExp(): bindingMap: " + tpp.bindingMap);
        Log.log(Log.MESSAGE, this, ":queryExp(): bindingMap: " + tpp.bindingMap);
        System.out.println("queryExp(): proof: " + tpp.proof);
        Log.log(Log.MESSAGE, this, ":queryExp(): proof: " + tpp.proof);
        java.util.List<String> proofStepsStr = new ArrayList<>();
        for (TPTPFormula ps : tpp.proof)
            proofStepsStr.add(ps.toString());
        jEdit.newFile(view);
        StringBuilder result = new StringBuilder();
        if (tpp.bindingMap != null && !tpp.bindingMap.isEmpty())
            result.append("Bindings: ").append(tpp.bindingMap);
        else if (tpp.bindings != null && !tpp.bindings.isEmpty())
            result.append("Bindings: ").append(tpp.bindings);
        if (tpp.proof == null || tpp.proof.isEmpty()) {
            result.append(tpp.status);
        } else {
            if (tpp.containsFalse)
                result.append("\n\n").append(StringUtil.arrayListToCRLFString(proofStepsStr));
            else
                result.append(tpp.status);
        }
        return result.toString();
    }

    @Override
    public void queryExp() {

        String contents = view.getEditPane().getTextArea().getSelectedText();
        Log.log(Log.MESSAGE, this, ":queryExp(): query with: " + contents);
        System.out.println("queryExp(): query with: " + contents);
        String dir = KBmanager.getMgr().getPref("kbDir") + File.separator;
        String type = "tptp";
        String outfile = dir + "temp-comb." + type;
        System.out.println("queryExp(): query on file: " + outfile);
        Log.log(Log.MESSAGE, this, ":queryExp(): query on file: " + outfile);
        Vampire vamp;
        EProver eprover;
        StringBuilder qlist = null;
        TPTP3ProofProcessor tpp = new TPTP3ProofProcessor();
        if (KBmanager.getMgr().prover == KBmanager.Prover.VAMPIRE) {
            vamp = kb.askVampire(contents, 30, 1);
            tpp.parseProofOutput(vamp.output, contents, kb, vamp.qlist);
            qlist = vamp.qlist;
            //System.out.println("queryExp(): completed query with result: " + StringUtil.arrayListToCRLFString(vamp.output));
            //Log.log(Log.WARNING,this,"queryExp(): completed query with result: " + StringUtil.arrayListToCRLFString(vamp.output));
        }
        if (KBmanager.getMgr().prover == KBmanager.Prover.EPROVER) {
            eprover = kb.askEProver(contents, 30, 1);
            try {
                //System.out.println("queryExp(): completed query with result: " + StringUtil.arrayListToCRLFString(eprover.output));
                //Log.log(Log.WARNING,this,"queryExp(): completed query with result: " + StringUtil.arrayListToCRLFString(eprover.output));
                tpp.parseProofOutput(eprover.output, contents, kb, eprover.qlist);
                qlist = eprover.qlist;
            } catch (Exception e) {
                Log.log(Log.ERROR, this, ":queryExp(): error " + Arrays.toString(e.getStackTrace()));
            }
        }
        tpp.processAnswersFromProof(qlist, contents);
        view.getTextArea().setText(queryResultString(tpp));
    }

    @Override
    public void browseTerm() {

        clearWarnAndErr();
        String contents = view.getEditPane().getTextArea().getSelectedText();
        if (!checkEditorContents(contents, "Please fully highlight a term to browse"))
            return;

        if (!StringUtil.emptyString(contents) && Formula.atom(contents)
                && kb.terms.contains(contents)) {
            String urlString = "http://sigma.ontologyportal.org:8443/sigma/Browse.jsp?kb=SUMO&lang=EnglishLanguage&flang=SUO-KIF&term="
                    + contents;
            if (Desktop.isDesktopSupported()) {
                try {
                    Desktop.getDesktop().browse(java.net.URI.create(urlString));
                } catch (IOException e) {
                    Log.log(Log.ERROR, this, ":browseTerm(): error " + Arrays.toString(e.getStackTrace()));
                }
            }
        }
    }

    /**
     * ***************************************************************
     * @return the line number of where the error/warning begins
     */
    private int getLineNum(String line) {

        int result = 0;
        Pattern p = Pattern.compile("line(:?) (\\d+)");
        Matcher m = p.matcher(line);
        if (m.find()) {
//            Log.log(Log.MESSAGE, this, ":getLineNum(): found line number: " + m.group(1));
            try {
                result = Integer.parseInt(m.group(2));
            } catch (NumberFormatException nfe) {}
        }
        if (result == 0) {
            p = Pattern.compile("line&#58; (\\d+)");
            m = p.matcher(line);
            if (m.find()) {
//                Log.log(Log.MESSAGE, this, ":getLineNum(): found line number: " + m.group(1));
                try {
                    result = Integer.parseInt(m.group(1));
                } catch (NumberFormatException nfe) {}
            }
        }
        if (result < 0)
            result = 0;
        return result;
    }

    /**
     * ***************************************************************
     * sigmaAntlr generates line offsets
     * @return the line offset of where the error/warning begins
     */
    private int getOffset(String line) {

        int result = 0;
        Pattern p = Pattern.compile("\\:(\\d+)\\:");
        Matcher m = p.matcher(line);
        if (m.find()) {
//            Log.log(Log.MESSAGE, this, ":getOffset(): found offset number: " + m.group(1));
            try {
                result = Integer.parseInt(m.group(1));
            } catch (NumberFormatException nfe) {}
        }
        if (result < 0)
            result = 0;
        return result;
    }

    /**
     * ***************************************************************
     * Utility class that contains searched term line and filepath information
     */
    public class FileSpec {

        public String filepath = "";
        public int line = -1;
    }

    /**
     * ***************************************************************
     * @return a FileSpec with searched term info
     */
    private FileSpec filespecFromForms(java.util.List<Formula> forms, String currentFName) {

        FileSpec fs = new FileSpec();
        for (Formula f : forms) {
            if (FileUtil.noPath(f.getSourceFile()).equals(currentFName) && !f.getSourceFile().endsWith("_Cache.kif")) {
                fs.filepath = f.sourceFile;
                fs.line = f.startLine - 1; // jedit starts from 0, SUMO starts from 1
                return fs;
            }
        }
        for (Formula f : forms) {
            if (!f.getSourceFile().endsWith("_Cache.kif")) {
                fs.filepath = f.sourceFile;
                fs.line = f.startLine - 1; // jedit starts from 0, SUMO starts from 1
                return fs;
            }
        }
        return fs;
    }

    /**
     * ***************************************************************
     * Note that the "definition" of a term is collection of axioms so look for,
     * in order: instance, subclass, subAttribute, subrelation, domain, documentation
     * @param term the term to search for
     * @return a FileSpec with searched term info
     */
    private FileSpec findDefn(String term) {

        if (StringUtil.emptyString(kif.filename))
            kif.filename = view.getBuffer().getPath();

        String currentFName = FileUtil.noPath(kif.filename);
        java.util.List<Formula> forms = kb.askWithRestriction(0, "instance", 1, term);
        if (forms != null && !forms.isEmpty())
            return(filespecFromForms(forms, currentFName));
        forms = kb.askWithRestriction(0, "subclass", 1, term);
        if (forms != null && !forms.isEmpty())
            return(filespecFromForms(forms, currentFName));
        forms = kb.askWithRestriction(0, "subAttribute", 1, term);
        if (forms != null && !forms.isEmpty())
            return(filespecFromForms(forms, currentFName));
        forms = kb.askWithRestriction(0, "subrelation", 1, term);
        if (forms != null && !forms.isEmpty())
            return(filespecFromForms(forms, currentFName));
        forms = kb.askWithRestriction(0, "domain", 1, term);
        if (forms != null && !forms.isEmpty())
            return(filespecFromForms(forms, currentFName));
        forms = kb.askWithRestriction(0, "documentation", 1, term);
        if (forms != null && !forms.isEmpty())
            return(filespecFromForms(forms, currentFName));
        return null; // nothing found
    }

    /** ***************************************************************
     * Warn the user about fully highlighting a term or formula
     *
     * @param contents the editor contents to check
     * @param msg the warning message to convey
     * @return indication of check pass/fail
     */
    private boolean checkEditorContents(String contents, String msg) {

        boolean retVal = true;
        if (contents == null || contents.isBlank() || contents.length() < 2) {

            errsrc.addError(ErrorSource.WARNING, kif.filename, 1, 0, 0, msg);
            if (log)
                Log.log(Log.WARNING, this, "checkEditorContents(): " + msg);
            retVal = false;
            // user fix before continuing
        }
        return retVal;
    }

    @Override
    public void gotoDefn() {

        clearWarnAndErr();
        String contents = view.getEditPane().getTextArea().getSelectedText();
        if (!checkEditorContents(contents, "Please fully highlight a term for definition"))
            return;

        if (StringUtil.emptyString(kif.filename))
            kif.filename = view.getBuffer().getPath();

        String currentFName = FileUtil.noPath(kif.filename);
        if (!StringUtil.emptyString(contents) && Formula.atom(contents)
                && kb.terms.contains(contents)) {
            FileSpec result = findDefn(contents);
            Log.log(Log.MESSAGE, this, ":gotoDefn(): file:"
                    + result.filepath + "\nline: " + (result.line+1));

            try {
                if (!FileUtil.noPath(result.filepath).equals(currentFName)) {
                    jEdit.openFile(view, result.filepath);
                    VFSManager.waitForRequests(); // <- Critical call to allow for complete Buffer loading!
                    int offset = view.getBuffer().getLineStartOffset(result.line);
                    view.getEditPane().getTextArea().moveCaretPosition(offset);
                }
            } catch (Exception e) {
                Log.log(Log.ERROR, this, "gotoDefn()", e);
            }
        }
        else
            Log.log(Log.WARNING, this, "gotoDefn() term: '" + contents + "' not in the KB");
    }

    /**
     * ***************************************************************
     * Performs the actual formula formatting
     *
     * @param contents the content (formula) to format
     */
    private String formatSelectBody(String contents) {

        if (!checkEditorContents(contents, "Please highlight a formula, or CNTL+A"))
            return null;
        if (!parseKif(contents))
            return null;
        if (StringUtil.emptyString(kif.filename))
            kif.filename = view.getBuffer().getPath();

        StringBuilder result = new StringBuilder();
        for (Formula f : kif.formulasOrdered.values())
            result.append(f);
        return result.toString();
    }

    @Override
    public void formatSelect() {

        clearWarnAndErr();
        String contents = view.getEditPane().getTextArea().getSelectedText();
        String result = formatSelectBody(contents);
        if (!StringUtil.emptyString(result))
            view.getEditPane().getTextArea().setSelectedText(result);
    }

    /**
     * ***************************************************************
     * Pass any KIF parse warnings and/or errors to the ErrorList Plugin. Also
     * any general warnings or errors (future capability)
     */
    private void logKifWarnAndErr() {

        int line, offset;
        for (String warn : kif.warningSet) {

            line = getLineNum(warn);
            offset = getOffset(warn);
            errsrc.addError(ErrorSource.WARNING, kif.filename, line == 0 ? line : line-1, offset, offset+1, warn);
        }
        for (String err : kif.errorSet) {

            line = getLineNum(err);
            offset = getOffset(err);
            errsrc.addError(ErrorSource.ERROR, kif.filename, line == 0 ? line : line-1, offset, offset+1, err);
        }

        // Not currently used, but good framework to have
        if (dw != null && (!dw.getErrorMessage().isBlank() || dw.getExtraMessages().length > 0))
            errsrc.addError(dw);
        if (de != null && (!de.getErrorMessage().isBlank() || de.getExtraMessages().length > 0))
            errsrc.addError(de);
    }

    /**
     * ***************************************************************
     * Clears the KIF instance collections to include warnings and errors
     */
    private void clearKif() {

        kif.errorSet.clear();
        kif.filename = "";
        kif.formulaMap.clear();
        kif.formulas.clear();
        kif.formulasOrdered.clear();
        kif.termFrequency.clear();
        kif.terms.clear();
        kif.warningSet.clear();
    }

    private void clearWarnAndErr() {

        if (!StringUtil.emptyString(kif.filename))
            errsrc.removeFileErrors(kif.filename);

        // Redundant calls if the clear button is also invoked
        jEdit.getAction("error-list-clear").invoke(view);
        errsrc.clear();
        // But good to invoke if the ErrorList is not yet visible

        clearKif();
        KButilities.clearErrors();
        kb.errors.clear();
        kb.warnings.clear();
        FormulaPreprocessor.errors.clear();
        SUMOtoTFAform.errors.clear();

        // Components of the ErrorListPanel: a Box on top of a JScrollPane.
        // We want the Box
        Container c = view.getDockableWindowManager().getDockable("error-list");

        // Toggles the ErrorList sweep (clean) button to clear errors from the UI
        if (c != null) { // can happen if ErrorList is not visible (selected)
            ErrorListPanel elp = (ErrorListPanel) c.getComponents()[0];

            // Get ErrorListPanel dimensions
            double elpWidth = elp.getWidth();
            double elpHeight = elp.getHeight();

            // Scale the coordinates. ErrorListPanel's (0, 0) starts at upper left corner
            // ELP dimensions are 1055x236 - Box coords are 1055x36
            double scaledX = ((elpWidth/2) * elpWidth) / elp.getBounds().width;
            double scaledY = ((36/2) * elpHeight) / elp.getBounds().height;

            // Get the Box at the scaled coordinates
            Box box = (Box) elp.getComponentAt((int) scaledX, (int) scaledY);
            RolloverButton btn = (RolloverButton) box.getComponents()[13];
            btn.doClick(); // click the clear btn
        }
    }

    @Override
    public void showStats() {

        clearWarnAndErr();
        Log.log(Log.MESSAGE, this, ":showStats(): starting");
        if (StringUtil.emptyString(kif.filename))
            kif.filename = view.getBuffer().getPath();
        String contents = view.getEditPane().getTextArea().getText();
        if (!parseKif(contents))
            return;

        Log.log(Log.MESSAGE, this, ":showStats(): path: " + kif.filename);
        String filename = FileUtil.noPath(kif.filename);
        StringBuilder stats = new StringBuilder();
        try {
            int termCount = 0;
            int otherTermCount = 0;
            FileSpec defn;
            String thisNoPath;
            for (String t : kif.terms) {
                if (t == null) {
                    Log.log(Log.WARNING, this, ":showStats(): null term");
                    continue;
                }
                defn = findDefn(t);
                if (defn == null) {
                    if (!Formula.isLogicalOperator(t)) {
                        Log.log(Log.WARNING, this, ":showStats(): no definition found for: " + t);
                        continue;
                    } else
                        thisNoPath = "";
                }
                else
                    thisNoPath = FileUtil.noPath(defn.filepath);
                //Log.log(Log.WARNING, this, " ");
                //Log.log(Log.WARNING, this, "showStats(): filename: " + filename);
                //Log.log(Log.WARNING, this, "showStats(): this no path: " + thisNoPath);
                if (thisNoPath.equals(filename) || StringUtil.emptyString(thisNoPath)) {
                    Log.log(Log.MESSAGE, this, ":showStats(): ******* in this file: " + t);
                    stats.append("******* in this file: ").append(t).append('\n');
                    termCount++;
                } else {
                    if (!Formula.isLogicalOperator(t)) {
                        //Log.log(Log.WARNING, this, "showStats(): not in this: " + t);
                        otherTermCount++;
                    }
                }
            }
            Log.log(Log.MESSAGE, this, ":showStats(): # terms: " + termCount);
            stats.append("# terms: ").append(termCount).append('\n');
            Log.log(Log.MESSAGE, this, ":showStats(): # terms used from other files: " + otherTermCount);
            stats.append("# terms used from other files: ").append(otherTermCount).append('\n');
            Log.log(Log.MESSAGE, this, ":showStats(): # axioms: " + kif.formulaMap.keySet().size());
            stats.append("# axioms: ").append(kif.formulaMap.keySet().size()).append('\n');
            int ruleCount = 0;
            for (Formula f : kif.formulaMap.values()) {
                if (f.isRule())
                    ruleCount++;
            }
            Log.log(Log.MESSAGE, this, ":showStats(): # rules: " + ruleCount);
            stats.append("# rules: ").append(ruleCount).append('\n');
            Log.log(Log.MESSAGE, this, ":showStats(): done reading kif file");
        } catch (Exception e) {
            try (Writer sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw)) {
                e.printStackTrace(pw);
                if (log)
                    Log.log(Log.ERROR, this, ":showStats()",e);
                String msg = "error loading kif file with " + contents.length() + " characters";
                errsrc.addError(ErrorSource.ERROR, kif.filename, 1, 0, 0, msg);
            } catch (IOException ex) {}
        }
        jEdit.newFile(view);
        view.getEditPane().getTextArea().setSelectedText(stats.toString());
        Log.log(Log.MESSAGE, this, ":showStats(): complete");
    }

    @Override
    public void checkErrors() {

        clearWarnAndErr();
        Log.log(Log.MESSAGE, this, ":checkErrors(): starting");
        if (StringUtil.emptyString(kif.filename))
            kif.filename = view.getBuffer().getPath();
        String contents = view.getEditPane().getTextArea().getText();

        Runnable r = () -> {
            checkErrorsBody(contents);
    //        errorListRefreshHack(); // do not want to do this, it disrupts UI threading
            Log.log(Log.MESSAGE, this, ":checkErrors(): complete");
        };
        KButilities.EXECUTOR_SERVICE.submit(r); // spawn this to get out if the EDT
    }

    /**
     * ***************************************************************
     * Hack to get the ErrorList to show all errors with multiple results mode on.
     * Should not have to do this, ErrorList refresh bug?
     */
//    private void errorListRefreshHack() {
//
//        ErrorSource.unregisterErrorSource(errsrc);
//        view.getBuffer().reload(view);
//        ErrorSource.registerErrorSource(errsrc);
//    }

    /** Utility method to parse KIF
     *
     * @param contents the contents of a KIF file to parse
     * @return true if successful parse, no error or warnings
     */
    private boolean parseKif(String contents) {

        boolean retVal = false;
        try (Reader r = new StringReader(contents)) {
            kif.parse(r);
            Log.log(Log.MESSAGE, this, ":parseKif(): done reading kif file");
            retVal = true;
        } catch (Exception e) {
            if (log)
                Log.log(Log.ERROR, this, ":checkErrorsBody()", e);
            String msg = "error loading kif file: " + kif.filename + " with " + contents.length() + " characters";
            errsrc.addError( ErrorSource.ERROR, kif.filename, 1, 0, 0, msg);
        } finally {
            logKifWarnAndErr();
        }
        return retVal;
    }

    /**
     * ***************************************************************
     * Check for a variety of syntactic and semantic errors and warnings in a
     * given buffer
     *
     * @param contents the content (SUO-KIF) to check
     */
    protected void checkErrorsBody(String contents) {

        if (!parseKif(contents))
            return;

        Log.log(Log.MESSAGE, this, ":checkErrorsBody(): success loading kif file with " + contents.length() + " characters");
        Log.log(Log.MESSAGE, this, ":checkErrorsBody(): filename: " + kif.filename);

        int counter = 0;
        Set<String> nbeTerms = new HashSet<>();
        Set<String> unkTerms = new HashSet<>();
        Set<String> result, unquant, terms;
        Set<Formula> processed;
        String err, term, msg;
        FileSpec defn;
        SuokifVisitor sv;
        for (Formula f : kif.formulaMap.values()) {
            Log.log(Log.MESSAGE, this, ":checkErrorsBody(): check formula:\n " + f);
            counter++;
            if (counter > KButilities.ONE_K) {
                Log.log(Log.NOTICE, this, ".");
                counter = 0;
            }
            // Check for syntax errors that KIF parse didn't catch
            sv = SuokifApp.process(Formula.textFormat(f.getFormula()));
            if (!sv.errors.isEmpty()) {
                int line, offset;
                for (String er : sv.errors) {
                    line = getLineNum(er);
                    offset = getOffset(er);
                    errsrc.addError(ErrorSource.ERROR, kif.filename, line == 0 ? line : line-1, offset, offset+1, er);
                }
            }
            //Log.log(Log.WARNING,this,"checking formula " + f.toString());
            if (Diagnostics.quantifierNotInStatement(f))
                errsrc.addError(ErrorSource.ERROR, kif.filename, f.startLine-1, f.endLine-1,0,"Quantifier not in statement");
            result = Diagnostics.singleUseVariables(f);
            if (result != null && !result.isEmpty())
                errsrc.addError(ErrorSource.WARNING, kif.filename, f.startLine-1, f.endLine-1,0, "Variable(s) only used once: " + result.toString());
            processed = fp.preProcess(f, false, kb);
            if (f.errors != null && !f.errors.isEmpty()) {
                for (String er : f.errors)
                    errsrc.addError(ErrorSource.ERROR, kif.filename, f.startLine-1, f.endLine-1, 0, er);
                for (String w : f.warnings)
                    errsrc.addError(ErrorSource.WARNING, kif.filename, f.startLine-1, f.endLine-1,0,w);
            }
            //Log.log(Log.WARNING,this,"checking variables in formula ");
            if (!KButilities.isValidFormula(kb, f.toString())) {
                for (String er : KButilities.errors) {
                    errsrc.addError(ErrorSource.ERROR, kif.filename, f.startLine-1, f.endLine-1, 0, er);
                    if (log)
                        Log.log(Log.ERROR, this, er);
                }
            }
            //Log.log(Log.WARNING,this,"done checking var types ");

            unquant = Diagnostics.unquantInConsequent(f);
            if (!unquant.isEmpty()) {
                err = "Unquantified var(s) " + unquant + " in consequent";
                errsrc.addError(ErrorSource.ERROR, kif.filename, f.startLine-1, f.endLine-1, 0, err);
                if (log)
                    Log.log(Log.WARNING, this, err);
            }

            // note that predicate variables can result in many relations being tried that don't
            // fit because of type inconsistencies, which then are rejected and not a formalization error
            // so ignore those cases (of size()>1)
            if (SUMOtoTFAform.errors != null && !f.errors.isEmpty() && processed.size() == 1) {
                for (String er : SUMOtoTFAform.errors) { // <- We might already have these from KButilities (tdn)
                    errsrc.addError(ErrorSource.ERROR, kif.filename, f.startLine-1, f.endLine-1, 0, er);
                    if (log)
                        Log.log( Log.ERROR, this, er);
                }
            }
            term = PredVarInst.hasCorrectArity(f, kb);
            if (!StringUtil.emptyString(term)) {
                msg = ("Arity error of predicate " + term);
                errsrc.addError(ErrorSource.ERROR, kif.filename, f.startLine-1, f.endLine-1, 0, msg);
                if (log)
                    Log.log(Log.ERROR, this, msg);
            }
            terms = f.collectTerms();
            Log.log(Log.MESSAGE, this, ":checkErrorsBody(): # terms in formula: " + terms.size());
            for (String t : terms) {
                if (Diagnostics.LOG_OPS.contains(t) || t.equals("Entity")
                        || Formula.isVariable(t) || StringUtil.isNumeric(t)
                        || StringUtil.isQuotedString(t)) {
                    continue;
                } else {
                    defn = findDefn(t);
                    if (defn == null) {
                        if (!unkTerms.contains(t)) {
                            unkTerms.add(t);
                            errsrc.addError(ErrorSource.WARNING, kif.filename, f.startLine-1, f.endLine-1, 0, "unknown term: " + t);
                            if (log)
                                Log.log(Log.WARNING, this, "unknown term: " + t);
                        }
                    }
                }
                if (Diagnostics.termNotBelowEntity(t, kb) && !nbeTerms.contains(t)) {
                    nbeTerms.add(t);
                    errsrc.addError(ErrorSource.ERROR, kif.filename, f.startLine-1, f.endLine-1, 0, "term not below Entity: " + t);
                    if (log)
                        Log.log(Log.WARNING, this, "term not below Entity: " + t);
                }
            }
        }
    }

    @Override
    public void toTPTP() {

        clearWarnAndErr();
        Log.log(Log.MESSAGE, this, ":toTPTP(): starting");
        if (StringUtil.emptyString(kif.filename))
            kif.filename = view.getBuffer().getPath();
        String contents = view.getEditPane().getTextArea().getText();

        if (!parseKif(contents))
            return;

        String selected = view.getEditPane().getTextArea().getSelectedText();
        if (!StringUtil.emptyString(selected))
            contents = selected;
        StringBuilder sb = new StringBuilder();
        try {
            //Log.log(Log.WARNING,this,"toTPTP(): done reading kif file");
            java.util.List<Formula> ordered = kif.lexicalOrder();
            String pred, tptpStr;
            TPTPVisitor sv;
            Map<String, TPTPFormula> hm;
            for (Formula f : ordered) {
                //Log.log(Log.WARNING,this,"toTPTP(): SUO-KIF: " + f.getFormula());
                pred = f.car();
                //Log.log(Log.WARNING,this,"toTPTP pred: " + pred);
                //Log.log(Log.WARNING,this,"toTPTP kb: " + kb);
                //Log.log(Log.WARNING,this,"toTPTP kb cache: " + kb.kbCache);
                //Log.log(Log.WARNING,this,"toTPTP gather pred vars: " + PredVarInst.gatherPredVars(kb,f));
                //if (f.predVarCache != null && f.predVarCache.size() > 0)
                //	Log.log(Log.WARNING,this,"toTPTP Formula.isHigherOrder(): pred var cache: " + f.predVarCache);
                if (f.isHigherOrder(kb) || (f.predVarCache != null && !f.predVarCache.isEmpty()))
                    continue;
                tptpStr = "fof(f4434,axiom," + SUMOformulaToTPTPformula.process(f, false) + ",[file('kb_" + f.getSourceFile() + "_" + f.startLine + "',unknown)]).";
                //Log.log(Log.WARNING,this,"toTPTP(): formatted as TPTP: " + tptpStr);
                sv = new TPTPVisitor();
                sv.parseString(tptpStr);
                hm = sv.result;
                for (String s : hm.keySet())
                    sb.append(hm.get(s).formula).append("\n\n");
            }
            jEdit.newFile(view);
            view.getTextArea().setText(sb.toString());
        } catch (Exception e) {
            if (log)
                Log.log(Log.ERROR, this, ":toTPTP()", e);
            try (Writer sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw)) {
                e.printStackTrace(pw);
                String msg = "error loading kif file with " + contents.length() + " characters";
                errsrc.addError(ErrorSource.ERROR, kif.filename, 1, 0, 0, msg);
            } catch (IOException ex) {}
        }
    }

    @Override
    public void fromTPTP() {

        clearWarnAndErr();
        Log.log(Log.MESSAGE, this, ":fromTPTP(): starting");
        String contents = view.getEditPane().getTextArea().getText();
        String selected = view.getEditPane().getTextArea().getSelectedText();
        if (!StringUtil.emptyString(selected))
            contents = selected;
        if (StringUtil.emptyString(kif.filename))
            kif.filename = view.getBuffer().getPath();
        try {
            TPTPVisitor sv = new TPTPVisitor();
            if (new File(kif.filename).exists())
                sv.parseFile(kif.filename);
            else
                sv.parseString(contents);
            Map<String, TPTPFormula> hm = sv.result;
            jEdit.newFile(view);
            StringBuilder result = new StringBuilder();
            for (String s : hm.keySet())
                result.append(hm.get(s).formula).append("\n\n");
            view.getTextArea().setText(result.toString());
            if (StringUtil.emptyString(result))
                Log.log(Log.WARNING, this, ":fromTPTP(): empty result");
            else
                Log.log(Log.MESSAGE, this, ":fromTPTP(): result.length: " + result.length());
        } catch (Exception e) {
            if (log)
                Log.log(Log.ERROR, this, ":fromTPTP()", e);
            try (Writer sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw)) {
                e.printStackTrace(pw);
                String msg = "error loading kif file with " + contents.length() + " characters";
                errsrc.addError( ErrorSource.ERROR, kif.filename, 1, 0, 0, msg);
            } catch (IOException ex) {}
        }
        Log.log(Log.MESSAGE, this, ":fromTPTP(): complete");
    }

    /**
     * ***************************************************************
     */
    public static void showHelp() {

        System.out.println("Diagnostics");
        System.out.println("  options:");
        System.out.println("  -h - show this help screen");
        System.out.println("  -d - <fname> - test diagnostics");
        System.out.println("  -q - run a default query");
    }

    /**
     * ***************************************************************
     * Test method for this class.
     * @param args command line arguments
     */
    public static void main(String args[]) {

        System.out.println("INFO: In SUMOjEdit.main()");
        SUMOjEdit sje = null;
        KB kb = null;
        if (args != null && args.length > 0 && args[0].equals("-h"))
            showHelp();
        else {
            SUMOtoTFAform.initOnce();
            sje = new SUMOjEdit();
            kb = sje.kb = SUMOtoTFAform.kb;
            sje.fp = SUMOtoTFAform.fp;
            sje.errsrc = new DefaultErrorSource(sje.getClass().getName(), null);
            ErrorSource.registerErrorSource(sje.errsrc);
        }

        if (args != null && args.length > 1 && args[0].equals("-d")) {
            String contents = String.join("\n", FileUtil.readLines(args[1], false));
            sje.kif.filename = args[1];
            sje.checkErrorsBody(contents);
        } else if (args != null && args.length > 0 && args[0].equals("-q")) {
            String contents = "(routeBetween ?X MenloParkCA MountainViewCA)";
            System.out.println("E input: " + contents);
            EProver eprover = kb.askEProver(contents, 30, 1);
            TPTP3ProofProcessor tpp = new TPTP3ProofProcessor();
            tpp.parseProofOutput(eprover.output, contents, kb, eprover.qlist);
            //tpp.processAnswersFromProof(contents);
            java.util.List<String> proofStepsStr = new ArrayList<>();
            for (TPTPFormula ps : tpp.proof) {
                proofStepsStr.add(ps.toString());
            }
            StringBuilder result = new StringBuilder();
            if (tpp.bindingMap != null && !tpp.bindingMap.isEmpty()) {
                result.append("Bindings: ").append(tpp.bindingMap);
            } else if (tpp.bindings != null && !tpp.bindings.isEmpty()) {
                result.append("Bindings: ").append(tpp.bindings);
            }
            if (tpp.proof == null || tpp.proof.isEmpty()) {
                result.append(tpp.status);
            } else {
                result.append("\n\n").append(StringUtil.arrayListToCRLFString(proofStepsStr));
            }
            System.out.println("\nE result: " + result.toString());
            System.out.println();
        } else {
            showHelp();
        }
    }
}
