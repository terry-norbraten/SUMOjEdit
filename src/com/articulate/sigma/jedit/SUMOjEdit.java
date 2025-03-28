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
import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.articulate.sigma.*;
import com.articulate.sigma.parsing.SuokifApp;
import com.articulate.sigma.parsing.SuokifVisitor;
import com.articulate.sigma.tp.*;
import com.articulate.sigma.trans.*;
import com.articulate.sigma.utils.*;

import errorlist.*;

import org.gjt.sp.jedit.*;
import org.gjt.sp.jedit.io.VFSManager;
import org.gjt.sp.jedit.msg.BufferUpdate;
import org.gjt.sp.jedit.msg.EditPaneUpdate;
import org.gjt.sp.jedit.msg.EditorExiting;
import org.gjt.sp.jedit.msg.VFSUpdate;
import org.gjt.sp.jedit.msg.ViewUpdate;
import org.gjt.sp.util.Log;

import tptp_parser.*;

/**
 * ***************************************************************
 *
 */
public class SUMOjEdit implements EBComponent, SUMOjEditActions, Runnable {

    private static boolean log = true;

    private final DefaultErrorSource errsrc;
    private final KIF kif;
    private final DefaultErrorSource.DefaultError dw;
    private final DefaultErrorSource.DefaultError de;

    protected FormulaPreprocessor fp;
    protected KB kb;
    private View view;

    boolean kbsInitialized;

    /** Initializes this plugin and loads the KBs
     * ***************************************************************
     */
    public SUMOjEdit() {

        kbsInitialized = false;

        Log.log(Log.MESSAGE, SUMOjEdit.this, ": SUMOKBtoTPTPKB.rapidParsing==" + SUMOKBtoTPTPKB.rapidParsing);
        Log.log(Log.MESSAGE, SUMOjEdit.this, ": initializing");

        // Initialize local plugin
        errsrc = new DefaultErrorSource(getClass().getName(), this.view);
        errorlist.ErrorSource.registerErrorSource(errsrc);
        kif = new KIF();
        kif.filename = "";
        dw = new DefaultErrorSource.DefaultError(errsrc, ErrorSource.WARNING, kif.filename, 1, 0, 0, "Parse Warnings:");
        de = new DefaultErrorSource.DefaultError(errsrc, ErrorSource.ERROR, kif.filename, 1, 0, 0, "Parse Errors:");
    }

    /**
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
        view.getJMenuBar().getSubElements()[8].menuSelectionChanged(true); // toggle the Plugins menu to populate all Plugin items

        // Top view menu bar / Enhanced menu item / Plugins menu / SUMOjEdit plugin menu
        view.getJMenuBar().getSubElements()[8].getSubElements()[0].getSubElements()[3].getComponent().setEnabled(kbsInitialized);

        // Now, the right click context menu of the editor's text area in the case of customized SUMOjEdit actions
        view.getEditPane().getTextArea().setRightClickPopupEnabled(kbsInitialized);

        // Will first initialize the KB
        SUMOtoTFAform.initOnce();
        kb = SUMOtoTFAform.kb;
        kbsInitialized = (kb != null);
        view.getJMenuBar().getSubElements()[8].menuSelectionChanged(false);
        view.getJMenuBar().getSubElements()[8].getSubElements()[0].getSubElements()[3].getComponent().setEnabled(kbsInitialized);
        view.getEditPane().getTextArea().setRightClickPopupEnabled(kbsInitialized);

        fp = SUMOtoTFAform.fp;
        Log.log(Log.MESSAGE, SUMOjEdit.this, ": kb: " + kb);
    }

    /** Props at: https://www.jedit.org/api/org/gjt/sp/jedit/msg/package-summary.html
     * ***************************************************************
     * @param msg the Edit Bus message to handle
     */
    @Override
    public void handleMessage(EBMessage msg) {

        if (msg instanceof BufferUpdate)
            bufferUpdate((BufferUpdate)msg);
        else if (msg instanceof VFSUpdate)
            vfsUpdate((VFSUpdate)msg);
        else if (msg instanceof ViewUpdate)
            viewUpdate((ViewUpdate)msg);
        else if (msg instanceof EditorExiting)
            editorExiting((EditorExiting)msg);
        else if (msg instanceof EditPaneUpdate)
            editPaneUpdate((EditPaneUpdate)msg);
    }

    /**
     * ***************************************************************
     */
    private void bufferUpdate(BufferUpdate bu) {

        if (bu.getView() == view)
            if (bu.getWhat() == BufferUpdate.DIRTY_CHANGED)
                System.out.println("DIRTY_CHANGED"); // file saved, or changed
    }

    /**
     * ***************************************************************
     */
    private void vfsUpdate(VFSUpdate vu) {

        System.out.println("VFS update"); // file saved
    }

    private void viewUpdate(ViewUpdate vu) {
        if (vu.getView() == view)
            if (vu.getWhat() == ViewUpdate.CLOSED) {
                unload();
                System.out.println("ViewUpdate.CLOSED, unloading"); // file saved, or changed
            }
    }

    /**
     * ***************************************************************
     */
    private void editorExiting(EditorExiting ee) {

        System.out.println("Editor exiting, unloading");
        unload();
    }

    /**
     * ***************************************************************
     */
    private void editPaneUpdate(EditPaneUpdate eu) {

        if (eu.getWhat() == EditPaneUpdate.BUFFERSET_CHANGED)
            System.out.println("BUFFERSET_CHANGED");
        else if (eu.getWhat() == EditPaneUpdate.BUFFER_CHANGED)
            System.out.println("BUFFER_CHANGED"); // switching between files or panes and closing panes
    }

    /**
     * ***************************************************************
     * Clean up resources upon shutdown
     */
    private void unload() {
        clearErrors();
        errsrc.clear();
        ErrorSource.unregisterErrorSource(errsrc);
    }

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
        for (TPTPFormula ps : tpp.proof) {
            proofStepsStr.add(ps.toString());
        }
        jEdit.newFile(view);
        StringBuilder result = new StringBuilder();
        if (tpp.bindingMap != null && !tpp.bindingMap.isEmpty()) {
            result.append("Bindings: ").append(tpp.bindingMap);
        } else if (tpp.bindings != null && !tpp.bindings.isEmpty()) {
            result.append("Bindings: ").append(tpp.bindings);
        }
        if (tpp.proof == null || tpp.proof.isEmpty()) {
            result.append(tpp.status);
        } else {
            if (tpp.containsFalse) {
                result.append("\n\n").append(StringUtil.arrayListToCRLFString(proofStepsStr));
            } else {
                result.append(tpp.status);
            }
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
                e.printStackTrace();
            }
        }
        tpp.processAnswersFromProof(qlist, contents);
        view.getTextArea().setText(queryResultString(tpp));
    }

    @Override
    public void browseTerm() {

        String contents = view.getEditPane().getTextArea().getSelectedText();
        if (!StringUtil.emptyString(contents) && Formula.atom(contents)
                && kb.terms.contains(contents)) {
            String urlString = "http://sigma.ontologyportal.org:8443/sigma/Browse.jsp?kb=SUMO&lang=EnglishLanguage&flang=SUO-KIF&term="
                    + contents;
            if (Desktop.isDesktopSupported()) {
                try {
                    Desktop.getDesktop().browse(java.net.URI.create(urlString));
                } catch (IOException e) {
                    Log.log(Log.ERROR, this, ":browseTerm(): error " + e.getMessage() + "\n" + Arrays.toString(e.getStackTrace()));
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
        Pattern p = Pattern.compile("line (\\d+)");
        Matcher m = p.matcher(line);
        if (m.find()) {
//            Log.log(Log.MESSAGE, this, ":getLineNum(): found line number: " + m.group(1));
            try {
                result = Integer.parseInt(m.group(1));
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
        return result;
    }

    /**
     * ***************************************************************
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
        return result;
    }

    /**
     * ***************************************************************
     */
    public class FileSpec {

        public String filepath = "";
        public int line = -1;
    }

    /**
     * ***************************************************************
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
        return null;
    }

    /**
     * ***************************************************************
     * Note that the "definition" of a term is collection of axioms so look for,
     * in order: instance, subclass, subAttribute, subrelation, domain, documentation
     */
    private FileSpec findDefn(String term) {

        String currentPath = view.getBuffer().getPath();
        String currentFName = FileUtil.noPath(currentPath);
        FileSpec fs = null;
        java.util.List<Formula> forms = kb.askWithRestriction(0, "instance", 1, term);
        if (forms != null && !forms.isEmpty())
            fs = filespecFromForms(forms, currentFName);
        forms = kb.askWithRestriction(0, "subclass", 1, term);
        if (forms != null && !forms.isEmpty())
            fs = filespecFromForms(forms, currentFName);
        forms = kb.askWithRestriction(0, "subAttribute", 1, term);
        if (forms != null && !forms.isEmpty())
            fs = filespecFromForms(forms, currentFName);
        forms = kb.askWithRestriction(0, "subrelation", 1, term);
        if (forms != null && !forms.isEmpty())
            fs = filespecFromForms(forms, currentFName);
        forms = kb.askWithRestriction(0, "domain", 1, term);
        if (forms != null && !forms.isEmpty())
            fs = filespecFromForms(forms, currentFName);
        forms = kb.askWithRestriction(0, "documentation", 1, term);
        if (forms != null && !forms.isEmpty())
            fs = filespecFromForms(forms, currentFName);
        return fs;
    }

    @Override
    public void gotoDefn() {

        String currentPath = view.getBuffer().getPath();
        String currentFName = FileUtil.noPath(currentPath);
        String contents = view.getEditPane().getTextArea().getSelectedText();
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
     */
    private String formatSelectBody(String contents) {

        kif.filename = this.view.getBuffer().getPath();
        try (StringReader r = new StringReader(contents)) {
            kif.parse(r);
        } catch (Exception e) {
            Log.log(Log.ERROR, this, ":formatSelect()", e);
            return null;
        } finally {
            logKifWarnAndErr();
        }
        if (kif.errorSet != null && !kif.errorSet.isEmpty()) {
            if (log)
                Log.log(Log.ERROR, this, ":formatSelect(): error loading kif file: "
                    + kif.errorSet);
            return null;
        }
        Log.log(Log.MESSAGE, this, ":formatSelect(): done reading kif file");
        StringBuilder result = new StringBuilder();
        for (Formula f : kif.formulasOrdered.values())
            result.append(Formula.textFormat(f.getFormula()));
        return result.toString();
    }

    /**
     * ***************************************************************
     * Pass any warnings and/or errors to the ErrorList Plugin
     */
    private void logKifWarnAndErr() {

        dw.setFilePath(kif.filename);
        for (String warn : kif.warningSet)
            dw.addExtraMessage(warn);
        de.setFilePath(kif.filename);
        for (String err : kif.errorSet)
            de.addExtraMessage(err);

        if (dw.getExtraMessages().length > 0)
            errsrc.addError(dw);
        if (de.getExtraMessages().length > 0)
            errsrc.addError(de);

        clearErrors();
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

    private void clearErrors() {

        clearKif();
        KButilities.clearErrors();
        kb.errors.clear();
        kb.warnings.clear();
        FormulaPreprocessor.errors.clear();
        SUMOtoTFAform.errors.clear();
    }

    @Override
    public void formatSelect() {

        String contents = view.getEditPane().getTextArea().getSelectedText();
        String result = formatSelectBody(contents);
        if (!StringUtil.emptyString(result))
            view.getEditPane().getTextArea().setSelectedText(result);
    }

    @Override
    public void showStats() {

        Log.log(Log.MESSAGE, this, ":showStats(): starting");
        kif.filename = view.getBuffer().getPath();
        String contents = view.getEditPane().getTextArea().getText();
        String path = kif.filename;
        Log.log(Log.MESSAGE, this, ":showStats(): path: " + path);
        String filename = FileUtil.noPath(path);
        StringBuilder stats = new StringBuilder();
        try (Reader r = new StringReader(contents)) {
            kif.parse(r);
            int termCount = 0;
            int otherTermCount = 0;
            FileSpec defn;
            String thisNoPath;
            for (String t : kif.terms) {
                if (t == null) {
                    Log.log(Log.WARNING, this, ":showStats(): null term ");
                    continue;
                }
                defn = findDefn(t);
                if (defn == null) {
                    Log.log(Log.WARNING, this, ":showStats(): no definition found for: " + t);
                    continue;
                }
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
                if (f.isRule()) {
                    ruleCount++;
                }
            }
            Log.log(Log.MESSAGE, this, ":showStats(): # rules: " + ruleCount);
            stats.append("# rules: ").append(ruleCount).append('\n');
            Log.log(Log.MESSAGE, this, ":showStats(): done reading kif file");
        } catch (Exception e) {
            try (Writer sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw)) {
                e.printStackTrace(pw);
                Log.log(Log.ERROR, this, ":showStats()",e);
                de.addExtraMessage("error loading kif file with " + contents.length() + " characters ");
            } catch (IOException ex) {}
        } finally {
            logKifWarnAndErr();
        }
        jEdit.newFile(view);
        view.getEditPane().getTextArea().setSelectedText(stats.toString());
        Log.log(Log.MESSAGE, this, ":showStats(): complete");
    }

    @Override
    public void checkErrors() {

        errsrc.clear();
        Log.log(Log.MESSAGE, this, ":checkErrors(): starting");
        String contents = view.getEditPane().getTextArea().getText();
        String path = view.getBuffer().getPath();
        checkErrorsBody(contents, path);
        Log.log(Log.MESSAGE, this, ":checkErrors(): complete");
    }

    /**
     * ***************************************************************
     * Check for a variety of syntactic and semantic errors and warnings in a
     * given buffer
     * @param contents the content (SUO-KIF) to check
     * @param path the path of the file containing SUO-KIF to check
     */
    protected void checkErrorsBody(String contents, String path) {

        kif.filename = path;
        try (Reader r = new StringReader(contents)) {
            kif.parse(r);
            Log.log(Log.MESSAGE, this, ":checkErrorsBody(): done reading kif file");
        } catch (Exception e) {
            if (log)
                Log.log(Log.ERROR, this, ":checkErrorsBody()", e);
            de.addExtraMessage("error loading kif file: " + path + " with " + contents.length() + " characters ");
            logKifWarnAndErr();
            return;
        }

        Log.log(Log.MESSAGE, this, ":checkErrorsBody(): success loading kif file with " + contents.length() + " characters ");
        Log.log(Log.MESSAGE, this, ":checkErrorsBody(): filename: " + path);

        try {
            int counter = 0;
            Set<String> nbeTerms = new HashSet<>();
            Set<String> unkTerms = new HashSet<>();
            Set<String> result, unquant, terms;
            Set<Formula> processed;
            String err, term, msg;
            java.util.List<Formula> forms;
            SuokifVisitor sv;
            int line, offset;
            for (Formula f : kif.formulaMap.values()) {
                Log.log(Log.MESSAGE, this, ":checkErrorsBody(): check formula:\n " + f);
                counter++;
                if (counter > 1000) {
                    Log.log(Log.NOTICE, this, ".");
                    counter = 0;
                }
                // Check for syntax errors that KIF doesn't catch
                sv = SuokifApp.process(Formula.textFormat(f.getFormula()));
                if (!sv.errors.isEmpty()) {
                    for (String er : sv.errors) {
                        line = getLineNum(er);
                        offset = getOffset(er);
                        errsrc.addError(ErrorSource.ERROR, kif.filename, line-1, offset, offset+1, er);
                        return;
                    }
                }
                //Log.log(Log.WARNING,this,"checking formula " + f.toString());
                if (Diagnostics.quantifierNotInStatement(f)) {
                    dw.addExtraMessage("Quantifier not in statement");
                }
                result = Diagnostics.singleUseVariables(f);
                if (result != null && !result.isEmpty()) {
                    dw.addExtraMessage("Variable(s) only used once: " + result.toString());
                }
                processed = fp.preProcess(f, false, kb);
                if (f.errors != null && !f.errors.isEmpty()) {
                    for (String er : f.errors) {
                        de.addExtraMessage(er);
                    }
                    for (String w : f.warnings) {
                        dw.addExtraMessage(w);
                    }
                }
                //Log.log(Log.WARNING,this,"checking variables in formula ");
                if (!KButilities.isValidFormula(kb, f.toString())) {
                    for (String e : KButilities.errors) {
                        de.addExtraMessage(e);
                        if (log)
                            Log.log(Log.ERROR, this, e);
                    }
                }
                //Log.log(Log.WARNING,this,"done checking var types ");

                unquant = Diagnostics.unquantInConsequent(f);
                if (!unquant.isEmpty()) {
                    err = "Unquantified var(s) " + unquant + " in consequent";
                    dw.addExtraMessage(err);
                    if (log)
                        Log.log(Log.WARNING, this, err);
                }

                // note that predicate variables can result in many relations being tried that don't
                // fit because of type inconsistencies, which then are rejected and not a formalization error
                // so ignore those cases (of size()>1)
                if (SUMOtoTFAform.errors != null && !f.errors.isEmpty() && processed.size() == 1) {
                    for (String er : SUMOtoTFAform.errors) {
                        de.addExtraMessage(er);
                        if (log)
                            Log.log( Log.ERROR, this, er);
                    }
                }
                term = PredVarInst.hasCorrectArity(f, kb);
                if (!StringUtil.emptyString(term)) {
                    msg = ("Arity error of predicate " + term);
                    de.addExtraMessage(msg);
                    if (log)
                        Log.log(Log.ERROR, this, msg);
                }
                terms = f.collectTerms();
                Log.log(Log.MESSAGE, this, ":checkErrorsBody(): # terms in formula: " + terms.size());
                for (String t : terms) {
                    if (Diagnostics.LOG_OPS.contains(t) || t.equals("Entity")
                            || Formula.isVariable(t) || StringUtil.isNumeric(t) || StringUtil.isQuotedString(t)) {
                        continue;
                    } else {
                        forms = kb.askWithRestriction(0, "instance", 1, t);
                        if (forms == null || forms.isEmpty()) {
                            forms = kb.askWithRestriction(0, "subclass", 1, t);
                            if (forms == null || forms.isEmpty()) {
                                forms = kb.askWithRestriction(0, "subAttribute", 1, t);
                                if (forms == null || forms.isEmpty()) {
                                    if (!unkTerms.contains(t))
                                        dw.addExtraMessage("unknown term: " + t);
                                    unkTerms.add(t);
                                    if (log)
                                        Log.log(Log.WARNING, this, "unknown term: " + t);
                                }
                            }
                        }
                    }
                    if (Diagnostics.termNotBelowEntity(t, kb) && !nbeTerms.contains(t)) {
                        dw.addExtraMessage("term not below entity: " + t);
                        nbeTerms.add(t);
                    }
                }
            }
        } finally {
            logKifWarnAndErr();
        }
    }

    @Override
    public void toTPTP() {

        Log.log(Log.MESSAGE, this, ":toTPTP(): starting");
        kif.filename = this.view.getBuffer().getPath();
        String contents = view.getEditPane().getTextArea().getText();
        String selected = view.getEditPane().getTextArea().getSelectedText();
        if (!StringUtil.emptyString(selected))
            contents = selected;
        StringBuilder sb = new StringBuilder();
        try (Reader r = new StringReader(contents)) {
            kif.parse(r);
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
                de.addExtraMessage("error loading kif file with " + contents.length() + " characters ");
            } catch (IOException ex) {}
        } finally {
            logKifWarnAndErr();
        }
    }

    @Override
    public void fromTPTP() {

        Log.log(Log.MESSAGE, this, ":fromTPTP(): starting");
        String contents = view.getEditPane().getTextArea().getText();
        String selected = view.getEditPane().getTextArea().getSelectedText();
        if (!StringUtil.emptyString(selected))
            contents = selected;
        String path = view.getBuffer().getPath();
        try {
            TPTPVisitor sv = new TPTPVisitor();
            if (new File(path).exists())
                sv.parseFile(path);
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
                de.addExtraMessage("error loading kif file with " + contents.length() + " characters ");
            } catch (IOException ex) {}
        } finally {
            logKifWarnAndErr();
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
    @SuppressWarnings("SleepWhileInLoop")
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
        }

        if (args != null && args.length > 1 && args[0].equals("-d")) {
            String contents = String.join("\n", FileUtil.readLines(args[1], false));
            sje.checkErrorsBody(contents, args[1]);
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
