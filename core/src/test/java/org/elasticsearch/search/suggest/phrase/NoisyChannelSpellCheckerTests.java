/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.suggest.phrase;

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.reverse.ReverseStringFilter;
import org.apache.lucene.analysis.shingle.ShingleFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymFilter;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.search.spell.DirectSpellChecker;
import org.apache.lucene.search.spell.SuggestMode;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.search.suggest.phrase.NoisyChannelSpellChecker.Result;
import org.elasticsearch.test.ESTestCase;
import org.junit.Test;

import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

public class NoisyChannelSpellCheckerTests extends ESTestCase {
    private final BytesRef space = new BytesRef(" ");
    private final BytesRef preTag = new BytesRef("<em>");
    private final BytesRef postTag = new BytesRef("</em>");
    private static final Splitter TAB_SPLITTER = Splitter.on("\t");

    @Test
    public void testSummits() throws IOException {
        RAMDirectory dir = new RAMDirectory();
        Map<String, Analyzer> mapping = new HashMap<>();
        mapping.put("body_ngram", new Analyzer() {

            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer t = new StandardTokenizer();
                ShingleFilter tf = new ShingleFilter(t, 2, 3);
                tf.setOutputUnigrams(false);
                return new TokenStreamComponents(t, new LowerCaseFilter(tf));
            }

        });

        mapping.put("body", new Analyzer() {

            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer t = new StandardTokenizer();
                return new TokenStreamComponents(t, new LowerCaseFilter(t));
            }

        });
        PerFieldAnalyzerWrapper wrapper = new PerFieldAnalyzerWrapper(new WhitespaceAnalyzer(), mapping);

        IndexWriterConfig conf = new IndexWriterConfig(wrapper);
        IndexWriter writer = new IndexWriter(dir, conf);
        BufferedReader reader = new BufferedReader(new InputStreamReader(NoisyChannelSpellCheckerTests.class.getResourceAsStream("/config/names.txt"), Charsets.UTF_8));
        String line = null;
        while ((line = reader.readLine()) != null) {
            List<String> parts = TAB_SPLITTER.splitToList(line);
            Document doc = new Document();
            doc.add(new Field("body", parts.get(0), TextField.TYPE_NOT_STORED));
            doc.add(new Field("body_ngram", parts.get(0), TextField.TYPE_NOT_STORED));
            writer.addDocument(doc);
        }

        DirectoryReader ir = DirectoryReader.open(writer, false);
        WordScorer wordScorer = new LaplaceScorer(ir, MultiFields.getTerms(ir, "body_ngram"), "body_ngram", 0.95d, new BytesRef(" "), 0.5f);

        NoisyChannelSpellChecker suggester = new NoisyChannelSpellChecker();
        DirectSpellChecker spellchecker = new DirectSpellChecker();
        spellchecker.setMinQueryLength(1);
        DirectCandidateGenerator generator = new DirectCandidateGenerator(spellchecker, "body", SuggestMode.SUGGEST_MORE_POPULAR, ir, 0.95, 5);
        Result result = suggester.getCorrections(wrapper, new BytesRef("mont blaac"), generator, 1, 1, ir, "body", wordScorer, 1, 2);
        Correction[] corrections = result.corrections;
        assertThat(corrections.length, equalTo(1));
        assertThat(corrections[0].join(space).utf8ToString(), equalTo("mont blanc"));
        assertThat(corrections[0].join(space, preTag, postTag).utf8ToString(), equalTo("mont <em>blanc</em>"));
        assertThat(result.cutoffScore, greaterThan(0d));

        result = suggester.getCorrections(wrapper, new BytesRef("mont blaac"), generator, 1, 1, ir, "body", wordScorer, 0, 1);
        corrections = result.corrections;
        assertThat(corrections.length, equalTo(1));
        assertThat(corrections[0].join(space).utf8ToString(), equalTo("mont blaac"));
        assertThat(corrections[0].join(space, preTag, postTag).utf8ToString(), equalTo("mont blaac"));
        assertThat(result.cutoffScore, equalTo(Double.MIN_VALUE));

        suggester = new NoisyChannelSpellChecker(0.85);
        wordScorer = new LaplaceScorer(ir, MultiFields.getTerms(ir, "body_ngram"), "body_ngram", 0.85d, new BytesRef(" "), 0.5f);
        corrections = suggester.getCorrections(wrapper, new BytesRef("Aiguill de Trioled"), generator, 0.5f, 4, ir, "body", wordScorer, 0, 2).corrections;
        assertThat(corrections.length, equalTo(4));
        assertThat(corrections[0].join(space).utf8ToString(), equalTo("aiguille de triolet"));
        assertThat(corrections[1].join(space).utf8ToString(), equalTo("aiguille de trioled"));
        assertThat(corrections[2].join(space).utf8ToString(), equalTo("aiguilles de triolet"));
        assertThat(corrections[3].join(space).utf8ToString(), equalTo("aiguill de triolet"));
        assertThat(corrections[0].join(space, preTag, postTag).utf8ToString(), equalTo("<em>aiguille</em> de <em>triolet</em>"));
        assertThat(corrections[1].join(space, preTag, postTag).utf8ToString(), equalTo("<em>aiguille</em> de trioled"));
        assertThat(corrections[2].join(space, preTag, postTag).utf8ToString(), equalTo("<em>aiguilles</em> de <em>triolet</em>"));
        assertThat(corrections[3].join(space, preTag, postTag).utf8ToString(), equalTo("aiguill de <em>triolet</em>"));

        corrections = suggester.getCorrections(wrapper, new BytesRef("Aiguill de Trioled"), generator, 0.5f, 4, ir, "body", wordScorer, 1, 2).corrections;
        assertThat(corrections.length, equalTo(4));
        assertThat(corrections[0].join(space).utf8ToString(), equalTo("aiguille de triolet"));
        assertThat(corrections[1].join(space).utf8ToString(), equalTo("aiguille de trioled"));
        assertThat(corrections[2].join(space).utf8ToString(), equalTo("aiguilles de triolet"));
        assertThat(corrections[3].join(space).utf8ToString(), equalTo("aiguill de triolet"));

        // Test some of the highlighting corner cases
        suggester = new NoisyChannelSpellChecker(0.85);
        wordScorer = new LaplaceScorer(ir, MultiFields.getTerms(ir, "body_ngram"), "body_ngram", 0.85d, new BytesRef(" "), 0.5f);
        corrections = suggester.getCorrections(wrapper, new BytesRef("Aiguill de Trioled"), generator, 4f, 4, ir, "body", wordScorer, 1, 2).corrections;
        assertThat(corrections.length, equalTo(4));
        assertThat(corrections[0].join(space).utf8ToString(), equalTo("aiguille de triolet"));
        assertThat(corrections[1].join(space).utf8ToString(), equalTo("aiguille de trioled"));
        assertThat(corrections[2].join(space).utf8ToString(), equalTo("aiguilles de triolet"));
        assertThat(corrections[3].join(space).utf8ToString(), equalTo("aiguill de triolet"));
        assertThat(corrections[0].join(space, preTag, postTag).utf8ToString(), equalTo("<em>aiguille</em> de <em>triolet</em>"));
        assertThat(corrections[1].join(space, preTag, postTag).utf8ToString(), equalTo("<em>aiguille</em> de trioled"));
        assertThat(corrections[2].join(space, preTag, postTag).utf8ToString(), equalTo("<em>aiguilles</em> de <em>triolet</em>"));
        assertThat(corrections[3].join(space, preTag, postTag).utf8ToString(), equalTo("aiguill de <em>triolet</em>"));

        // test synonyms

        Analyzer analyzer = new Analyzer() {

            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer t = new StandardTokenizer();
                TokenFilter filter = new LowerCaseFilter(t);
                try {
                    SolrSynonymParser parser = new SolrSynonymParser(true, false, new WhitespaceAnalyzer());
                    ((SolrSynonymParser) parser).parse(new StringReader("piz => piz, bernina"));
                    filter = new SynonymFilter(filter, parser.build(), true);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return new TokenStreamComponents(t, filter);
            }
        };

        spellchecker.setAccuracy(0.0f);
        spellchecker.setMinPrefix(1);
        spellchecker.setMinQueryLength(1);
        suggester = new NoisyChannelSpellChecker(0.85);
        wordScorer = new LaplaceScorer(ir, MultiFields.getTerms(ir, "body_ngram"), "body_ngram", 0.85d, new BytesRef(" "), 0.5f);
        corrections = suggester.getCorrections(analyzer, new BytesRef("piz berina"), generator, 2, 4, ir, "body", wordScorer, 1, 2).corrections;
        assertThat(corrections[0].join(space).utf8ToString(), equalTo("piz bernina"));
        assertThat(corrections[0].join(space, preTag, postTag).utf8ToString(), equalTo("piz <em>bernina</em>"));

        generator = new DirectCandidateGenerator(spellchecker, "body", SuggestMode.SUGGEST_MORE_POPULAR, ir, 0.85, 10, null, analyzer, MultiFields.getTerms(ir, "body"));
        corrections = suggester.getCorrections(analyzer, new BytesRef("paz berina"), generator, 2, 4, ir, "body", wordScorer, 1, 2).corrections;
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("piz bernina"));
        assertThat(corrections[0].join(space, preTag, postTag).utf8ToString(), equalTo("<em>piz bernina</em>"));

        // Make sure that user supplied text is not marked as highlighted in the presence of a synonym filter
        generator = new DirectCandidateGenerator(spellchecker, "body", SuggestMode.SUGGEST_MORE_POPULAR, ir, 0.85, 10, null, analyzer, MultiFields.getTerms(ir, "body"));
        corrections = suggester.getCorrections(analyzer, new BytesRef("piz berina"), generator, 2, 4, ir, "body", wordScorer, 1, 2).corrections;
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("piz bernina"));
        assertThat(corrections[0].join(space, preTag, postTag).utf8ToString(), equalTo("piz <em>bernina</em>"));
    }

    @Test
    public void testSummitsMultiGenerator() throws IOException {
        RAMDirectory dir = new RAMDirectory();
        Map<String, Analyzer> mapping = new HashMap<>();
        mapping.put("body_ngram", new Analyzer() {

            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer t = new StandardTokenizer();
                ShingleFilter tf = new ShingleFilter(t, 2, 3);
                tf.setOutputUnigrams(false);
                return new TokenStreamComponents(t, new LowerCaseFilter(tf));
            }

        });

        mapping.put("body", new Analyzer() {

            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer t = new StandardTokenizer();
                return new TokenStreamComponents(t, new LowerCaseFilter(t));
            }

        });
        mapping.put("body_reverse", new Analyzer() {

            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer t = new StandardTokenizer();
                return new TokenStreamComponents(t, new ReverseStringFilter(new LowerCaseFilter(t)));
            }

        });
        PerFieldAnalyzerWrapper wrapper = new PerFieldAnalyzerWrapper(new WhitespaceAnalyzer(), mapping);

        IndexWriterConfig conf = new IndexWriterConfig(wrapper);
        IndexWriter writer = new IndexWriter(dir, conf);
        BufferedReader reader = new BufferedReader(new InputStreamReader(NoisyChannelSpellCheckerTests.class.getResourceAsStream("/config/names.txt"), Charsets.UTF_8));
        String line = null;
        while ((line = reader.readLine()) != null) {
            List<String> parts = TAB_SPLITTER.splitToList(line);
            Document doc = new Document();
            doc.add(new Field("body", parts.get(0), TextField.TYPE_NOT_STORED));
            doc.add(new Field("body_reverse", parts.get(0), TextField.TYPE_NOT_STORED));
            doc.add(new Field("body_ngram", parts.get(0), TextField.TYPE_NOT_STORED));
            writer.addDocument(doc);
        }

        DirectoryReader ir = DirectoryReader.open(writer, false);
        LaplaceScorer wordScorer = new LaplaceScorer(ir, MultiFields.getTerms(ir, "body_ngram"), "body_ngram", 0.95d, new BytesRef(" "), 0.5f);
        NoisyChannelSpellChecker suggester = new NoisyChannelSpellChecker();
        DirectSpellChecker spellchecker = new DirectSpellChecker();
        spellchecker.setMinQueryLength(1);
        DirectCandidateGenerator forward = new DirectCandidateGenerator(spellchecker, "body", SuggestMode.SUGGEST_ALWAYS, ir, 0.95, 10);
        DirectCandidateGenerator reverse = new DirectCandidateGenerator(spellchecker, "body_reverse", SuggestMode.SUGGEST_ALWAYS, ir, 0.95, 10, wrapper, wrapper,  MultiFields.getTerms(ir, "body_reverse"));
        CandidateGenerator generator = new MultiCandidateGeneratorWrapper(10, forward, reverse);

        Correction[] corrections = suggester.getCorrections(wrapper, new BytesRef("mont blacn"), generator, 1, 1, ir, "body", wordScorer, 1, 2).corrections;
        assertThat(corrections.length, equalTo(1));
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("mont blanc"));

        generator = new MultiCandidateGeneratorWrapper(5, forward, reverse);
        corrections = suggester.getCorrections(wrapper, new BytesRef("mont blaac"), generator, 1, 1, ir, "body", wordScorer, 1, 2).corrections;
        assertThat(corrections.length, equalTo(1));
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("mont blanc"));

        corrections = suggester.getCorrections(wrapper, new BytesRef("mont lacbn"), forward, 1, 1, ir, "body", wordScorer, 1, 2).corrections;
        assertThat(corrections.length, equalTo(0)); // only use forward with constant prefix

        corrections = suggester.getCorrections(wrapper, new BytesRef("mont blnac"), generator, 2, 1, ir, "body", wordScorer, 1, 2).corrections;
        assertThat(corrections.length, equalTo(1));
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("mont blanc"));
        corrections = suggester.getCorrections(wrapper, new BytesRef("Aaguille de Triolet"), generator, 0.5f, 4, ir, "body", wordScorer, 0, 2).corrections;
        assertThat(corrections.length, equalTo(4));
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguille de triolet"));
        assertThat(corrections[1].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguille de triboulet"));
        assertThat(corrections[2].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguilles de triolet"));


        corrections = suggester.getCorrections(wrapper, new BytesRef("Aaguille de Triolet"), generator, 0.5f, 1, ir, "body", wordScorer, 1.5f, 2).corrections;
        assertThat(corrections.length, equalTo(1));
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguille de triolet"));

        corrections = suggester.getCorrections(wrapper, new BytesRef("Aiguill de Triolet"), generator, 0.5f, 1, ir, "body", wordScorer, 1.5f, 2).corrections;
        assertThat(corrections.length, equalTo(1));
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguille de triolet"));

        // Test a special case where one of the suggest term is unchanged by the postFilter, 'II' here is unchanged by the reverse analyzer.
        corrections = suggester.getCorrections(wrapper, new BytesRef("Mont Blaac"), generator, 1, 1, ir, "body", wordScorer, 1, 2).corrections;
        assertThat(corrections.length, equalTo(1));
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("mont blanc"));
    }

    @Test
    public void testSummitsTrigram() throws IOException {


        RAMDirectory dir = new RAMDirectory();
        Map<String, Analyzer> mapping = new HashMap<>();
        mapping.put("body_ngram", new Analyzer() {

            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer t = new StandardTokenizer();
                ShingleFilter tf = new ShingleFilter(t, 2, 3);
                tf.setOutputUnigrams(false);
                return new TokenStreamComponents(t, new LowerCaseFilter(tf));
            }

        });

        mapping.put("body", new Analyzer() {

            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer t = new StandardTokenizer();
                return new TokenStreamComponents(t, new LowerCaseFilter(t));
            }

        });
        PerFieldAnalyzerWrapper wrapper = new PerFieldAnalyzerWrapper(new WhitespaceAnalyzer(), mapping);

        IndexWriterConfig conf = new IndexWriterConfig(wrapper);
        IndexWriter writer = new IndexWriter(dir, conf);
        BufferedReader reader = new BufferedReader(new InputStreamReader(NoisyChannelSpellCheckerTests.class.getResourceAsStream("/config/names.txt"), Charsets.UTF_8));
        String line = null;
        while ((line = reader.readLine()) != null) {
            Document doc = new Document();
            doc.add(new Field("body", line, TextField.TYPE_NOT_STORED));
            doc.add(new Field("body_ngram", line, TextField.TYPE_NOT_STORED));
            writer.addDocument(doc);
        }

        DirectoryReader ir = DirectoryReader.open(writer, false);
        WordScorer wordScorer = new LinearInterpoatingScorer(ir, MultiFields.getTerms(ir, "body_ngram"), "body_ngram", 0.85d, new BytesRef(" "), 0.5, 0.4, 0.1);

        NoisyChannelSpellChecker suggester = new NoisyChannelSpellChecker();
        DirectSpellChecker spellchecker = new DirectSpellChecker();
        spellchecker.setMinQueryLength(1);
        DirectCandidateGenerator generator = new DirectCandidateGenerator(spellchecker, "body", SuggestMode.SUGGEST_MORE_POPULAR, ir, 0.95, 5);
        Correction[] corrections = suggester.getCorrections(wrapper, new BytesRef("mont blaac"), generator, 1, 1, ir, "body", wordScorer, 1, 3).corrections;
        assertThat(corrections.length, equalTo(1));
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("mont blanc"));

        corrections = suggester.getCorrections(wrapper, new BytesRef("mont blaac"), generator, 1, 1, ir, "body", wordScorer, 1, 1).corrections;
        assertThat(corrections.length, equalTo(0));

        wordScorer = new LinearInterpoatingScorer(ir, MultiFields.getTerms(ir, "body_ngram"), "body_ngram", 0.85d, new BytesRef(" "), 0.5, 0.4, 0.1);
        corrections = suggester.getCorrections(wrapper, new BytesRef("Aiguill de Trioled"), generator, 0.5f, 4, ir, "body", wordScorer, 0, 3).corrections;
        assertThat(corrections.length, equalTo(4));
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguille de triolet"));
        assertThat(corrections[1].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguilles de triolet"));
        assertThat(corrections[2].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguille de trioled"));
        assertThat(corrections[3].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguilles de trioled"));




        corrections = suggester.getCorrections(wrapper, new BytesRef("Aiguill de Trioled"), generator, 0.5f, 4, ir, "body", wordScorer, 1, 3).corrections;
        assertThat(corrections.length, equalTo(4));
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguille de triolet"));
        assertThat(corrections[1].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguilles de triolet"));
        assertThat(corrections[2].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguille de trioled"));
        assertThat(corrections[3].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguilles de trioled"));


        corrections = suggester.getCorrections(wrapper, new BytesRef("Aiguill de Trioled"), generator, 0.5f, 1, ir, "body", wordScorer, 100, 3).corrections;
        assertThat(corrections.length, equalTo(1));
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguille de triolet"));


        // test synonyms

        Analyzer analyzer = new Analyzer() {

            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer t = new StandardTokenizer();
                TokenFilter filter = new LowerCaseFilter(t);
                try {
                    SolrSynonymParser parser = new SolrSynonymParser(true, false, new WhitespaceAnalyzer());
                    ((SolrSynonymParser) parser).parse(new StringReader("piz => piz, bernina"));
                    filter = new SynonymFilter(filter, parser.build(), true);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return new TokenStreamComponents(t, filter);
            }
        };

        spellchecker.setAccuracy(0.0f);
        spellchecker.setMinPrefix(1);
        spellchecker.setMinQueryLength(1);
        suggester = new NoisyChannelSpellChecker(0.95);
        wordScorer = new LinearInterpoatingScorer(ir, MultiFields.getTerms(ir, "body_ngram"), "body_ngram", 0.95d, new BytesRef(" "),  0.5, 0.4, 0.1);
        corrections = suggester.getCorrections(analyzer, new BytesRef("piz berina"), generator, 2, 4, ir, "body", wordScorer, 1, 3).corrections;
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("piz bernina"));

        generator = new DirectCandidateGenerator(spellchecker, "body", SuggestMode.SUGGEST_MORE_POPULAR, ir, 0.95, 10, null, analyzer, MultiFields.getTerms(ir, "body"));
        corrections = suggester.getCorrections(analyzer, new BytesRef("paz bernina"), generator, 2, 4, ir, "body", wordScorer, 1, 3).corrections;
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("piz bernina"));


        wordScorer = new StupidBackoffScorer(ir, MultiFields.getTerms(ir, "body_ngram"), "body_ngram", 0.85d, new BytesRef(" "), 0.4);
        corrections = suggester.getCorrections(wrapper, new BytesRef("Aiguill de Trioled"), generator, 0.5f, 2, ir, "body", wordScorer, 0, 3).corrections;
        assertThat(corrections.length, equalTo(2));
        assertThat(corrections[0].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguille de triolet"));
        assertThat(corrections[1].join(new BytesRef(" ")).utf8ToString(), equalTo("aiguilles de triolet"));
    }
}