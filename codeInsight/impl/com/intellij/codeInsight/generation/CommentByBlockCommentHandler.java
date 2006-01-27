package com.intellij.codeInsight.generation;

import com.intellij.codeInsight.CodeInsightActionHandler;
import com.intellij.codeInsight.CodeInsightUtil;
import com.intellij.featureStatistics.FeatureUsageTracker;
import com.intellij.ide.highlighter.custom.CustomFileTypeLexer;
import com.intellij.ide.highlighter.custom.impl.CustomFileType;
import com.intellij.lang.Commenter;
import com.intellij.lang.Language;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.codeStyle.Indent;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.IntArrayList;
import com.intellij.util.text.CharArrayUtil;

public class CommentByBlockCommentHandler implements CodeInsightActionHandler {
  private Project myProject;
  private Editor myEditor;
  private PsiFile myFile;
  private Document myDocument;

  public void invoke(Project project, Editor editor, PsiFile file) {
    myProject = project;
    myEditor = editor;
    myFile = file;

    myDocument = editor.getDocument();

    if (!myFile.isWritable()) {
      if (!FileDocumentManager.fileForDocumentCheckedOutSuccessfully(myDocument, project)) {
        return;
      }
    }
    FeatureUsageTracker.getInstance().triggerFeatureUsed("codeassists.comment.block");
    final Commenter commenter = getCommenter();
    if (commenter == null) return;

    final SelectionModel selectionModel = myEditor.getSelectionModel();

    final String prefix = commenter.getBlockCommentPrefix();
    final String suffix = commenter.getBlockCommentSuffix();
    if (prefix == null || suffix == null) return;

    TextRange commentedRange = findCommentedRange(commenter);
    if (commentedRange != null) {
      final int commentStart = commentedRange.getStartOffset();
      final int commentEnd = commentedRange.getEndOffset();
      int selectionStart = commentStart;
      int selectionEnd = commentEnd;
      if (selectionModel.hasSelection()) {
        selectionStart = selectionModel.getSelectionStart();
        selectionEnd = selectionModel.getSelectionEnd();
      }
      if ((commentStart < selectionStart || commentStart >= selectionEnd) && (commentEnd <= selectionStart || commentEnd > selectionEnd)) {
        commentRange(selectionStart, selectionEnd, prefix, suffix);
      }
      else {
        uncommentRange(commentedRange, prefix, suffix);
      }
    }
    else {
      if (selectionModel.hasBlockSelection()) {
        final LogicalPosition start = selectionModel.getBlockStart();
        final LogicalPosition end = selectionModel.getBlockEnd();
        int startColumn = Math.min(start.column, end.column);
        int endColumn = Math.max(start.column, end.column);
        int startLine = Math.min(start.line, end.line);
        int endLine = Math.max(start.line, end.line);

        for (int i = startLine; i <= endLine; i++) {
          editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(i, endColumn));
          EditorModificationUtil.insertStringAtCaret(editor, suffix, true, true);
        }

        for (int i = startLine; i <= endLine; i++) {
          editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(i, startColumn));
          EditorModificationUtil.insertStringAtCaret(editor, prefix, true, true);
        }
      }
      else if (selectionModel.hasSelection()) {
        int selectionStart = selectionModel.getSelectionStart();
        int selectionEnd = selectionModel.getSelectionEnd();
        commentRange(selectionStart, selectionEnd, prefix, suffix);
      }
      else {
        final LogicalPosition caretPosition = myEditor.getCaretModel().getLogicalPosition();
        EditorUtil.fillVirtualSpaceUntil(editor, caretPosition.column, caretPosition.line);
        int caretOffset = myEditor.getCaretModel().getOffset();
        myDocument.insertString(caretOffset, prefix + suffix);
        myEditor.getCaretModel().moveToOffset(caretOffset + prefix.length());
      }
    }
  }

  private TextRange findCommentedRange(final Commenter commenter) {
    final FileType fileType = myFile.getFileType();
    if (fileType instanceof CustomFileType) {
      Lexer lexer = new CustomFileTypeLexer(((CustomFileType)fileType).getSyntaxTable());
      final CharSequence text = myDocument.getCharsSequence();
      final int caretOffset = myEditor.getCaretModel().getOffset();
      int commentStart = CharArrayUtil.lastIndexOf(text, commenter.getBlockCommentPrefix(), caretOffset);
      if (commentStart == -1) return null;
      char[] chars = CharArrayUtil.fromSequence(text);
      lexer.start(chars, commentStart, text.length());
      if (lexer.getTokenType() == CustomHighlighterTokenType.MULTI_LINE_COMMENT && lexer.getTokenEnd() >= caretOffset) {
        return new TextRange(commentStart, lexer.getTokenEnd());
      }
      return null;
    }

    TextRange commentedRange = null;
    PsiElement comment = findCommentAtCaret();
    if (comment != null) {
      String commentText = comment.getText();
      String prefix = commenter.getBlockCommentPrefix();
      String suffix = commenter.getBlockCommentSuffix();
      if (!commentText.startsWith(prefix) || !commentText.endsWith(suffix)) return null;
      commentedRange = comment.getTextRange();
    }
    return commentedRange;
  }

  private Commenter getCommenter() {
    final FileType fileType = myFile.getFileType();
    if (fileType instanceof CustomFileType) {
      return ((CustomFileType)fileType).getCommenter();
    }

    final SelectionModel selectionModel = myEditor.getSelectionModel();
    int caretOffset = myEditor.getCaretModel().getOffset();
    Language lang = getLanguageAtOffset(caretOffset, false);
    if (lang == null) return null;
    if (selectionModel.hasSelection()) {
      Language l1 = getLanguageAtOffset(selectionModel.getSelectionStart(), false);
      Language l2 = getLanguageAtOffset(selectionModel.getSelectionEnd(), true);
      if (!Comparing.equal(lang, l1) || !Comparing.equal(lang, l2)) {
        // selection covers multiple languages use language of the file to comment
        lang = myFile.getLanguage();
      }
    }

    return lang.getCommenter();
  }

  private PsiElement findCommentAtCaret() {
    PsiElement elt = myFile.findElementAt(myEditor.getCaretModel().getOffset());
    if (elt == null) return null;
    return PsiTreeUtil.getParentOfType(elt, PsiComment.class, false);
  }

  private Language getLanguageAtOffset(final int offset, boolean backward) {
    PsiElement elt = myFile.findElementAt(offset);
    if (backward && elt instanceof PsiWhiteSpace) {
      elt = elt.getPrevSibling();
    }
    if (elt == null) {
      if (offset > 0) elt = myFile.findElementAt(offset - 1);
      if (elt == null) return null;
    }
    return elt.getLanguage();
  }

  public boolean startInWriteAction() {
    return true;
  }

  public void commentRange(int startOffset, int endOffset, String commentPrefix, String commentSuffix) {
    CharSequence chars = myDocument.getCharsSequence();
    LogicalPosition caretPosition = myEditor.getCaretModel().getLogicalPosition();

    if (startOffset == 0 || chars.charAt(startOffset - 1) == '\n' || chars.charAt(startOffset - 1) == '\r') {
      if (endOffset == myDocument.getTextLength() || chars.charAt(endOffset - 1) == '\n' || chars.charAt(endOffset - 1) == '\r') {
        CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(myProject);
        CodeStyleSettings settings = CodeStyleSettingsManager.getSettings(myProject);
        String space;
        if (!settings.BLOCK_COMMENT_AT_FIRST_COLUMN) {
          final FileType fileType = myFile.getFileType();
          int line1 = myEditor.offsetToLogicalPosition(startOffset).line;
          int line2 = myEditor.offsetToLogicalPosition(endOffset - 1).line;
          Indent minIndent = CodeInsightUtil.getMinLineIndent(myProject, myDocument, line1, line2, fileType);
          if (minIndent == null) {
            minIndent = codeStyleManager.zeroIndent();
          }
          space = codeStyleManager.fillIndent(minIndent, fileType);
        }
        else {
          space = "";
        }
        insertNestedComments(chars, startOffset, endOffset, space + commentPrefix + "\n", space + commentSuffix + "\n");
        myEditor.getSelectionModel().removeSelection();
        LogicalPosition pos = new LogicalPosition(caretPosition.line + 1, caretPosition.column);
        myEditor.getCaretModel().moveToLogicalPosition(pos);
        myEditor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
        return;
      }
    }

    insertNestedComments(chars, startOffset, endOffset, commentPrefix, commentSuffix);
    myEditor.getSelectionModel().removeSelection();
    LogicalPosition pos = new LogicalPosition(caretPosition.line, caretPosition.column + commentPrefix.length());
    myEditor.getCaretModel().moveToLogicalPosition(pos);
    myEditor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
  }

  private void insertNestedComments(CharSequence chars, int startOffset, int endOffset, String commentPrefix, String commentSuffix) {
    String normalizedPrefix = commentPrefix.trim();
    String normalizedSuffix = commentSuffix.trim();
    IntArrayList nestedCommentPrefixes = new IntArrayList();
    IntArrayList nestedCommentSuffixes = new IntArrayList();
    for (int i = startOffset; i < endOffset; ++i) {
      if (CharArrayUtil.regionMatches(chars, i, normalizedPrefix)) {
        nestedCommentPrefixes.add(i);
      }
      else {
        if (CharArrayUtil.regionMatches(chars, i, normalizedSuffix)) {
          nestedCommentSuffixes.add(i);
        }
      }
    }
    myDocument.insertString(endOffset, commentSuffix);
    // process nested comments in back order
    int i = nestedCommentPrefixes.size() - 1, j = nestedCommentSuffixes.size() - 1;
    while (i >= 0 && j >= 0) {
      final int prefixIndex = nestedCommentPrefixes.get(i);
      final int suffixIndex = nestedCommentSuffixes.get(j);
      if (prefixIndex > suffixIndex) {
        myDocument.insertString(prefixIndex, commentSuffix);
        --i;
      }
      else {
        myDocument.insertString(suffixIndex + commentSuffix.length(), commentPrefix);
        --j;
      }
    }
    while (i >= 0) {
      final int prefixIndex = nestedCommentPrefixes.get(i);
      myDocument.insertString(prefixIndex, commentSuffix);
      --i;
    }
    while (j >= 0) {
      final int suffixIndex = nestedCommentSuffixes.get(j);
      myDocument.insertString(suffixIndex + commentSuffix.length(), commentPrefix);
      --j;
    }
    myDocument.insertString(startOffset, commentPrefix);
  }

  public void uncommentRange(TextRange range, String commentPrefix, String commentSuffix) {
    CharSequence chars = myDocument.getCharsSequence();
    int startOffset = range.getStartOffset();
    boolean endsProperly = CharArrayUtil.regionMatches(chars, range.getEndOffset() - commentSuffix.length(), commentSuffix);

    int delOffset1 = startOffset;
    int delOffset2 = startOffset + commentPrefix.length();
    int offset1 = CharArrayUtil.shiftBackward(chars, delOffset1 - 1, " \t");
    if (offset1 < 0 || chars.charAt(offset1) == '\n' || chars.charAt(offset1) == '\r') {
      int offset2 = CharArrayUtil.shiftForward(chars, delOffset2, " \t");
      if (offset2 == myDocument.getTextLength() || chars.charAt(offset2) == '\r' || chars.charAt(offset2) == '\n') {
        delOffset1 = offset1 + 1;
        if (offset2 < myDocument.getTextLength()) {
          delOffset2 = offset2 + 1;
          if (chars.charAt(offset2) == '\r' && offset2 + 1 < myDocument.getTextLength() && chars.charAt(offset2 + 1) == '\n') {
            delOffset2++;
          }
        }
      }
    }

    myDocument.deleteString(delOffset1, delOffset2);
    chars = myDocument.getCharsSequence();

    if (endsProperly) {
      int shift = delOffset2 - delOffset1;
      int delOffset3 = range.getEndOffset() - shift - commentSuffix.length();
      int delOffset4 = range.getEndOffset() - shift;
      int offset3 = CharArrayUtil.shiftBackward(chars, delOffset3 - 1, " \t");
      if (offset3 < 0 || chars.charAt(offset3) == '\n' || chars.charAt(offset3) == '\r') {
        int offset4 = CharArrayUtil.shiftForward(chars, delOffset4, " \t");
        if (offset4 == myDocument.getTextLength() || chars.charAt(offset4) == '\r' || chars.charAt(offset4) == '\n') {
          delOffset3 = offset3 + 1;
          if (offset4 < myDocument.getTextLength()) {
            delOffset4 = offset4 + 1;
            if (chars.charAt(offset4) == '\r' && offset4 + 1 < myDocument.getTextLength() && chars.charAt(offset4 + 1) == '\n') {
              delOffset4++;
            }
          }
        }
      }
      myDocument.deleteString(delOffset3, delOffset4);
    }
  }
}