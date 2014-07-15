/*
 * Copyright 2012 Sebastian Annies, Hamburg
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.googlecode.mp4parser.authoring.builder;

import com.coremedia.iso.BoxParser;
import com.coremedia.iso.IsoFile;
import com.coremedia.iso.IsoTypeWriter;
import com.coremedia.iso.boxes.*;
import com.coremedia.iso.boxes.fragment.*;
import com.googlecode.mp4parser.BasicContainer;
import com.googlecode.mp4parser.DataSource;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Sample;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.tracks.CencEncyprtedTrack;
import com.googlecode.mp4parser.boxes.cenc.CencSampleAuxiliaryDataFormat;
import com.googlecode.mp4parser.boxes.ultraviolet.SampleEncryptionBox;
import com.googlecode.mp4parser.util.Path;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static com.googlecode.mp4parser.util.CastUtils.l2i;

/**
 * Creates a fragmented MP4 file.
 */
public class FragmentedMp4Builder implements Mp4Builder {
    private static final Logger LOG = Logger.getLogger(FragmentedMp4Builder.class.getName());

    protected FragmentIntersectionFinder intersectionFinder;

    public FragmentedMp4Builder() {
    }

    public Date getDate() {
        return new Date();
    }

    public List<String> getAllowedHandlers() {
        return Arrays.asList("soun", "vide");
    }

    public Box createFtyp(Movie movie) {
        List<String> minorBrands = new LinkedList<String>();
        minorBrands.add("isom");
        minorBrands.add("iso2");
        minorBrands.add("avc1");
        return new FileTypeBox("isom", 0, minorBrands);
    }

    /**
     * Sorts fragments by start time.
     *
     * @param tracks          the list of tracks to returned sorted
     * @param cycle           current fragment (sorting may vary between the fragments)
     * @param intersectionMap a map from tracks to their fragments' first samples.
     * @return the list of tracks in order of appearance in the fragment
     */
    protected List<Track> sortTracksInSequence(List<Track> tracks, final int cycle, final Map<Track, long[]> intersectionMap) {
        tracks = new LinkedList<Track>(tracks);
        Collections.sort(tracks, new Comparator<Track>() {
            public int compare(Track o1, Track o2) {
                long startSample1 = intersectionMap.get(o1)[cycle];
                // one based sample numbers - the first sample is 1
                long startSample2 = intersectionMap.get(o2)[cycle];
                // one based sample numbers - the first sample is 1

                // now let's get the start times
                long[] decTimes1 = o1.getSampleDurations();
                long[] decTimes2 = o2.getSampleDurations();
                long startTime1 = 0;
                long startTime2 = 0;

                for (int i = 1; i < startSample1; i++) {
                    startTime1 += decTimes1[i - 1];
                }
                for (int i = 1; i < startSample2; i++) {
                    startTime2 += decTimes2[i - 1];
                }

                // and compare
                return (int) (((double) startTime1 / o1.getTrackMetaData().getTimescale() - (double) startTime2 / o2.getTrackMetaData().getTimescale()) * 100);
            }
        });
        return tracks;
    }

    /**
     * Progressive Download Box required as per iso2 brand
     */
    protected Box createPdin(Movie movie) {
        ProgressiveDownloadInformationBox pdin = new ProgressiveDownloadInformationBox();
        LinkedList<ProgressiveDownloadInformationBox.Entry> entries = new LinkedList<ProgressiveDownloadInformationBox.Entry>();
        //todo: not a tiny bit exact but who cares
        double size = 0;
        long durationInSeconds = 0;
        for (Track track : movie.getTracks()) {
            long tracksDuration = track.getDuration() / track.getTrackMetaData().getTimescale();
            if (tracksDuration > durationInSeconds) {
                durationInSeconds = tracksDuration;
            }
            List<Sample> samples = track.getSamples();
            if (samples.size() < 10000) {
                for (Sample sample : samples) {
                    size += sample.getSize();
                }
            } else {
                long _size = 0;
                for (int i = 0; i < 10000; i++) {
                    _size += samples.get(i).getSize();
                }
                size += _size * samples.size() / 10000;
            }

        }
        size *= 1.2; // security margin
        double byteRate = size / durationInSeconds;
        long dlRate = 10000;

        do {
            //long waitTime = (videoRate - dlRate) * durationInSeconds / dlRate;
            long waitTimeMilliseconds = Math.round((byteRate * durationInSeconds) / dlRate - durationInSeconds) * 1000;

            ProgressiveDownloadInformationBox.Entry entry =
                    new ProgressiveDownloadInformationBox.Entry(dlRate, waitTimeMilliseconds > 0 ? waitTimeMilliseconds + 3000 : 0);
            entries.add(entry);
            dlRate *= 2; // double dlRate 10k 20k 40k 80k 160k 320k 640k 1.2m 2.5m 5m
        } while (byteRate > dlRate);
        pdin.setEntries(entries);

        return pdin;
    }

    protected List<Box> createMoofMdat(final Movie movie) {
        List<Box> moofsMdats = new LinkedList<Box>();
        HashMap<Track, long[]> intersectionMap = new HashMap<Track, long[]>();
        int maxNumberOfFragments = 0;
        for (Track track : movie.getTracks()) {
            long[] intersects = intersectionFinder.sampleNumbers(track);
            intersectionMap.put(track, intersects);
            maxNumberOfFragments = Math.max(maxNumberOfFragments, intersects.length);
        }


        int sequence = 1;
        // this loop has two indices:

        for (int cycle = 0; cycle < maxNumberOfFragments; cycle++) {

            final List<Track> sortedTracks = sortTracksInSequence(movie.getTracks(), cycle, intersectionMap);

            for (Track track : sortedTracks) {
                if (getAllowedHandlers().isEmpty() || getAllowedHandlers().contains(track.getHandler())) {
                    long[] startSamples = intersectionMap.get(track);
                    sequence = createFragment(moofsMdats, track, startSamples, cycle, sequence);
                }
            }
        }
        return moofsMdats;
    }

    protected int createFragment(List<Box> moofsMdats, Track track, long[] startSamples, int cycle, int sequence) {
        //some tracks may have less fragments -> skip them
        if (cycle < startSamples.length) {

            long startSample = startSamples[cycle];
            // one based sample numbers - the first sample is 1
            long endSample = cycle + 1 < startSamples.length ? startSamples[cycle + 1] : track.getSamples().size() + 1;

            // if startSample == endSample the cycle is empty!
            if (startSample != endSample) {
                moofsMdats.add(createMoof(startSample, endSample, track, sequence));
                moofsMdats.add(createMdat(startSample, endSample, track, sequence++));
            }
        }
        return sequence;
    }

    /**
     * {@inheritDoc}
     */
    public Container build(Movie movie) {
        LOG.fine("Creating movie " + movie);
        if (intersectionFinder == null) {
            Track refTrack = null;
            for (Track track : movie.getTracks()) {
                if (track.getHandler().equals("vide")) {
                    refTrack = track;
                    break;
                }
            }
            intersectionFinder = new SyncSampleIntersectFinderImpl(movie, refTrack, -1);
        }
        BasicContainer isoFile = new BasicContainer();


        isoFile.addBox(createFtyp(movie));
        //isoFile.addBox(createPdin(movie));
        isoFile.addBox(createMoov(movie));

        for (Box box : createMoofMdat(movie)) {
            isoFile.addBox(box);
        }
        isoFile.addBox(createMfra(movie, isoFile));

        return isoFile;
    }

    protected Box createMdat(final long startSample, final long endSample, final Track track, final int i) {

        class Mdat implements Box {
            Container parent;
            long size_ = -1;

            public Container getParent() {
                return parent;
            }

            public long getOffset() {
                throw new RuntimeException("Doesn't have any meaning for programmatically created boxes");
            }

            public void setParent(Container parent) {
                this.parent = parent;
            }

            public long getSize() {
                if (size_ != -1) return size_;
                long size = 8; // I don't expect 2gig fragments
                for (Sample sample : getSamples(startSample, endSample, track, i)) {
                    size += sample.getSize();
                }
                size_ = size;
                return size;
            }

            public String getType() {
                return "mdat";
            }

            public void getBox(WritableByteChannel writableByteChannel) throws IOException {
                ByteBuffer header = ByteBuffer.allocate(8);
                IsoTypeWriter.writeUInt32(header, l2i(getSize()));
                header.put(IsoFile.fourCCtoBytes(getType()));
                header.rewind();
                writableByteChannel.write(header);

                List<Sample> samples = getSamples(startSample, endSample, track, i);
                for (Sample sample : samples) {
                    sample.writeTo(writableByteChannel);
                }


            }

            public void parse(DataSource fileChannel, ByteBuffer header, long contentSize, BoxParser boxParser) throws IOException {

            }
        }

        return new Mdat();
    }

    protected void createTfhd(long startSample, long endSample, Track track, int sequenceNumber, TrackFragmentBox parent) {
        TrackFragmentHeaderBox tfhd = new TrackFragmentHeaderBox();
        SampleFlags sf = new SampleFlags();

        tfhd.setDefaultSampleFlags(sf);
        tfhd.setBaseDataOffset(-1);
        tfhd.setTrackId(track.getTrackMetaData().getTrackId());
        parent.addBox(tfhd);
    }

    protected void createMfhd(long startSample, long endSample, Track track, int sequenceNumber, MovieFragmentBox parent) {
        MovieFragmentHeaderBox mfhd = new MovieFragmentHeaderBox();
        mfhd.setSequenceNumber(sequenceNumber);
        parent.addBox(mfhd);
    }

    protected void createTraf(long startSample, long endSample, Track track, int sequenceNumber, MovieFragmentBox parent) {
        TrackFragmentBox traf = new TrackFragmentBox();
        parent.addBox(traf);
        createTfhd(startSample, endSample, track, sequenceNumber, traf);
        createTfdt(startSample, track, traf);
        createTrun(startSample, endSample, track, sequenceNumber, traf);

        if (track instanceof CencEncyprtedTrack) {
            createSenc(startSample, endSample, (CencEncyprtedTrack) track, sequenceNumber, traf);
            createSaio(startSample, endSample, (CencEncyprtedTrack) track, sequenceNumber, traf);
            createSaiz(startSample, endSample, (CencEncyprtedTrack) track, sequenceNumber, traf);
        }


    }

    protected void createSenc(long startSample, long endSample, CencEncyprtedTrack track, int sequenceNumber, TrackFragmentBox parent) {
        SampleEncryptionBox senc = new SampleEncryptionBox();
        senc.setSubSampleEncryption(track.hasSubSampleEncryption());
        senc.setEntries(track.getSampleEncryptionEntries().subList(l2i(startSample - 1), l2i(endSample - 1)));
        parent.addBox(senc);
    }

    protected void createSaio(long startSample, long endSample, CencEncyprtedTrack track, int sequenceNumber, TrackFragmentBox parent) {
        SampleAuxiliaryInformationOffsetsBox saio = new SampleAuxiliaryInformationOffsetsBox();
        parent.addBox(saio);
        assert parent.getBoxes(TrackRunBox.class).size() == 1 : "Don't know how to deal with multiple Track Run Boxes when encrypting";

        long offset = 0;
        offset += 8; // traf header till 1st child box
        for (Box box : parent.getBoxes()) {
            if (box instanceof SampleEncryptionBox) {
                offset += ((SampleEncryptionBox) box).getOffsetToFirstIV();
                break;
            } else {
                offset += box.getSize();
            }
        }
        MovieFragmentBox moof = (MovieFragmentBox) parent.getParent();
        offset += 16; // traf header till 1st child box
        for (Box box : moof.getBoxes()) {
            if (box == parent) {
                break;
            } else {
                offset += box.getSize();
            }

        }

        saio.setOffsets(Collections.singletonList(offset));

    }

    protected void createSaiz(long startSample, long endSample, CencEncyprtedTrack track, int sequenceNumber, TrackFragmentBox parent) {
        SampleAuxiliaryInformationSizesBox saiz = new SampleAuxiliaryInformationSizesBox();
        List<Short> sizes = new ArrayList<Short>(l2i(endSample - startSample));
        for (CencSampleAuxiliaryDataFormat auxiliaryDataFormat :
                track.getSampleEncryptionEntries().subList(l2i(startSample - 1), l2i(endSample - 1))) {
            sizes.add((short) auxiliaryDataFormat.getSize());

        }
        saiz.setSampleInfoSizes(sizes);
        parent.addBox(saiz);
    }


    /**
     * Gets all samples starting with <code>startSample</code> (one based -&gt; one is the first) and
     * ending with <code>endSample</code> (exclusive).
     *
     * @param startSample    low endpoint (inclusive) of the sample sequence
     * @param endSample      high endpoint (exclusive) of the sample sequence
     * @param track          source of the samples
     * @param sequenceNumber the fragment index of the requested list of samples
     * @return a <code>List&lt;ByteBuffer&gt;</code> of raw samples
     */
    protected List<Sample> getSamples(long startSample, long endSample, Track track, int sequenceNumber) {
        // since startSample and endSample are one-based substract 1 before addressing list elements
        return track.getSamples().subList(l2i(startSample) - 1, l2i(endSample) - 1);
    }

    /**
     * Gets the sizes of a sequence of samples.
     *
     * @param startSample    low endpoint (inclusive) of the sample sequence
     * @param endSample      high endpoint (exclusive) of the sample sequence
     * @param track          source of the samples
     * @param sequenceNumber the fragment index of the requested list of samples
     * @return the sample sizes in the given interval
     */
    protected long[] getSampleSizes(long startSample, long endSample, Track track, int sequenceNumber) {
        List<Sample> samples = getSamples(startSample, endSample, track, sequenceNumber);

        long[] sampleSizes = new long[samples.size()];
        for (int i = 0; i < sampleSizes.length; i++) {
            sampleSizes[i] = samples.get(i).getSize();
        }
        return sampleSizes;
    }

    protected void createTfdt(long startSample, Track track, TrackFragmentBox parent) {
        TrackFragmentBaseMediaDecodeTimeBox tfdt = new TrackFragmentBaseMediaDecodeTimeBox();
        tfdt.setVersion(1);
        long startTime = 0;
        long[] times = track.getSampleDurations();
        for (int i = 1; i < startSample; i++) {
            startTime += times[i];
        }
        tfdt.setBaseMediaDecodeTime(startTime);
        parent.addBox(tfdt);
    }

    /**
     * Creates one or more track run boxes for a given sequence.
     *
     * @param startSample    low endpoint (inclusive) of the sample sequence
     * @param endSample      high endpoint (exclusive) of the sample sequence
     * @param track          source of the samples
     * @param sequenceNumber the fragment index of the requested list of samples
     * @param parent         the created box must be added to this box
     */
    protected void createTrun(long startSample, long endSample, Track track, int sequenceNumber, TrackFragmentBox parent) {
        TrackRunBox trun = new TrackRunBox();
        long[] sampleSizes = getSampleSizes(startSample, endSample, track, sequenceNumber);

        trun.setSampleDurationPresent(true);
        trun.setSampleSizePresent(true);
        List<TrackRunBox.Entry> entries = new ArrayList<TrackRunBox.Entry>(l2i(endSample - startSample));


        List<CompositionTimeToSample.Entry> compositionTimeEntries = track.getCompositionTimeEntries();
        int compositionTimeQueueIndex = 0;
        CompositionTimeToSample.Entry[] compositionTimeQueue =
                compositionTimeEntries != null && compositionTimeEntries.size() > 0 ?
                        compositionTimeEntries.toArray(new CompositionTimeToSample.Entry[compositionTimeEntries.size()]) : null;
        long compositionTimeEntriesLeft = compositionTimeQueue != null ? compositionTimeQueue[compositionTimeQueueIndex].getCount() : -1;


        trun.setSampleCompositionTimeOffsetPresent(compositionTimeEntriesLeft > 0);

        // fast forward composition stuff
        for (long i = 1; i < startSample; i++) {
            if (compositionTimeQueue != null) {
                //trun.setSampleCompositionTimeOffsetPresent(true);
                if (--compositionTimeEntriesLeft == 0 && (compositionTimeQueue.length - compositionTimeQueueIndex) > 1) {
                    compositionTimeQueueIndex++;
                    compositionTimeEntriesLeft = compositionTimeQueue[compositionTimeQueueIndex].getCount();
                }
            }
        }

        boolean sampleFlagsRequired = (track.getSampleDependencies() != null && !track.getSampleDependencies().isEmpty() ||
                track.getSyncSamples() != null && track.getSyncSamples().length != 0);

        trun.setSampleFlagsPresent(sampleFlagsRequired);

        for (int i = 0; i < sampleSizes.length; i++) {
            TrackRunBox.Entry entry = new TrackRunBox.Entry();
            entry.setSampleSize(sampleSizes[i]);
            if (sampleFlagsRequired) {
                //if (false) {
                SampleFlags sflags = new SampleFlags();

                if (track.getSampleDependencies() != null && !track.getSampleDependencies().isEmpty()) {
                    SampleDependencyTypeBox.Entry e = track.getSampleDependencies().get(i);
                    sflags.setSampleDependsOn(e.getSampleDependsOn());
                    sflags.setSampleIsDependedOn(e.getSampleIsDependentOn());
                    sflags.setSampleHasRedundancy(e.getSampleHasRedundancy());
                }
                if (track.getSyncSamples() != null && track.getSyncSamples().length > 0) {
                    // we have to mark non-sync samples!
                    if (Arrays.binarySearch(track.getSyncSamples(), startSample + i) >= 0) {
                        sflags.setSampleIsDifferenceSample(false);
                        sflags.setSampleDependsOn(2);
                    } else {
                        sflags.setSampleIsDifferenceSample(true);
                        sflags.setSampleDependsOn(1);
                    }
                }
                // i don't have sample degradation
                entry.setSampleFlags(sflags);

            }

            entry.setSampleDuration(track.getSampleDurations()[l2i(startSample + i - 1)]);

            if (compositionTimeQueue != null) {
                entry.setSampleCompositionTimeOffset(compositionTimeQueue[compositionTimeQueueIndex].getOffset());
                if (--compositionTimeEntriesLeft == 0 && (compositionTimeQueue.length - compositionTimeQueueIndex) > 1) {
                    compositionTimeQueueIndex++;
                    compositionTimeEntriesLeft = compositionTimeQueue[compositionTimeQueueIndex].getCount();
                }
            }
            entries.add(entry);
        }

        trun.setEntries(entries);

        parent.addBox(trun);
    }

    /**
     * Creates a 'moof' box for a given sequence of samples.
     *
     * @param startSample    low endpoint (inclusive) of the sample sequence
     * @param endSample      high endpoint (exclusive) of the sample sequence
     * @param track          source of the samples
     * @param sequenceNumber the fragment index of the requested list of samples
     * @return the list of TrackRun boxes.
     */
    protected Box createMoof(long startSample, long endSample, Track track, int sequenceNumber) {
        MovieFragmentBox moof = new MovieFragmentBox();
        createMfhd(startSample, endSample, track, sequenceNumber, moof);
        createTraf(startSample, endSample, track, sequenceNumber, moof);

        TrackRunBox firstTrun = moof.getTrackRunBoxes().get(0);
        firstTrun.setDataOffset(1); // dummy to make size correct
        firstTrun.setDataOffset((int) (8 + moof.getSize())); // mdat header + moof size

        return moof;
    }

    /**
     * Creates a single 'mvhd' movie header box for a given movie.
     *
     * @param movie the concerned movie
     * @return an 'mvhd' box
     */
    protected Box createMvhd(Movie movie) {
        MovieHeaderBox mvhd = new MovieHeaderBox();
        mvhd.setVersion(1);
        mvhd.setCreationTime(getDate());
        mvhd.setModificationTime(getDate());
        mvhd.setDuration(0);//no duration in moov for fragmented movies
        long movieTimeScale = movie.getTimescale();
        mvhd.setTimescale(movieTimeScale);
        // find the next available trackId
        long nextTrackId = 0;
        for (Track track : movie.getTracks()) {
            nextTrackId = nextTrackId < track.getTrackMetaData().getTrackId() ? track.getTrackMetaData().getTrackId() : nextTrackId;
        }
        mvhd.setNextTrackId(++nextTrackId);
        return mvhd;
    }

    /**
     * Creates a fully populated 'moov' box with all child boxes. Child boxes are:
     * <ul>
     * <li>{@link #createMvhd(com.googlecode.mp4parser.authoring.Movie) mvhd}</li>
     * <li>{@link #createMvex(com.googlecode.mp4parser.authoring.Movie)  mvex}</li>
     * <li>a {@link #createTrak(com.googlecode.mp4parser.authoring.Track, com.googlecode.mp4parser.authoring.Movie)  trak} for every track</li>
     * </ul>
     *
     * @param movie the concerned movie
     * @return fully populated 'moov'
     */
    protected Box createMoov(Movie movie) {
        MovieBox movieBox = new MovieBox();

        movieBox.addBox(createMvhd(movie));
        for (Track track : movie.getTracks()) {
            movieBox.addBox(createTrak(track, movie));
        }
        movieBox.addBox(createMvex(movie));

        // metadata here
        return movieBox;
    }

    /**
     * Creates a 'tfra' - track fragment random access box for the given track with the isoFile.
     * The tfra contains a map of random access points with time as key and offset within the isofile
     * as value.
     *
     * @param track   the concerned track
     * @param isoFile the track is contained in
     * @return a track fragment random access box.
     */
    protected Box createTfra(Track track, Container isoFile) {
        TrackFragmentRandomAccessBox tfra = new TrackFragmentRandomAccessBox();
        tfra.setVersion(1); // use long offsets and times
        List<TrackFragmentRandomAccessBox.Entry> offset2timeEntries = new LinkedList<TrackFragmentRandomAccessBox.Entry>();

        TrackExtendsBox trex = null;
        List<Box> trexs = Path.getPaths(isoFile, "moov/mvex/trex");
        for (Box innerTrex : trexs) {
            if (((TrackExtendsBox) innerTrex).getTrackId() == track.getTrackMetaData().getTrackId()) {
                trex = (TrackExtendsBox) innerTrex;
            }
        }

        long offset = 0;
        long duration = 0;

        for (Box box : isoFile.getBoxes()) {
            if (box instanceof MovieFragmentBox) {
                List<TrackFragmentBox> trafs = ((MovieFragmentBox) box).getBoxes(TrackFragmentBox.class);
                for (int i = 0; i < trafs.size(); i++) {
                    TrackFragmentBox traf = trafs.get(i);

                    if (traf.getTrackFragmentHeaderBox().getTrackId() == track.getTrackMetaData().getTrackId()) {

                        // here we are at the offset required for the current entry.
                        List<TrackRunBox> truns = traf.getBoxes(TrackRunBox.class);
                        for (int j = 0; j < truns.size(); j++) {
                            List<TrackFragmentRandomAccessBox.Entry> offset2timeEntriesThisTrun = new LinkedList<TrackFragmentRandomAccessBox.Entry>();
                            TrackRunBox trun = truns.get(j);
                            for (int k = 0; k < trun.getEntries().size(); k++) {
                                TrackRunBox.Entry trunEntry = trun.getEntries().get(k);
                                SampleFlags sf;
                                if (k == 0 && trun.isFirstSampleFlagsPresent()) {
                                    sf = trun.getFirstSampleFlags();
                                } else if (trun.isSampleFlagsPresent()) {
                                    sf = trunEntry.getSampleFlags();
                                } else {
                                    sf = trex.getDefaultSampleFlags();
                                }
                                if (sf == null && track.getHandler().equals("vide")) {
                                    throw new RuntimeException("Cannot find SampleFlags for video track but it's required to build tfra");
                                }
                                if (sf == null || sf.getSampleDependsOn() == 2) {
                                    offset2timeEntriesThisTrun.add(new TrackFragmentRandomAccessBox.Entry(
                                            duration,
                                            offset,
                                            i + 1, j + 1, k + 1));
                                }
                                duration += trunEntry.getSampleDuration();
                            }
                            if (offset2timeEntriesThisTrun.size() == trun.getEntries().size() && trun.getEntries().size() > 0) {
                                // Oooops every sample seems to be random access sample
                                // is this an audio track? I don't care.
                                // I just use the first for trun sample for tfra random access
                                offset2timeEntries.add(offset2timeEntriesThisTrun.get(0));
                            } else {
                                offset2timeEntries.addAll(offset2timeEntriesThisTrun);
                            }
                        }
                    }
                }
            }


            offset += box.getSize();
        }
        tfra.setEntries(offset2timeEntries);
        tfra.setTrackId(track.getTrackMetaData().getTrackId());
        return tfra;
    }

    /**
     * Creates a 'mfra' - movie fragment random access box for the given movie in the given
     * isofile. Uses {@link #createTfra(com.googlecode.mp4parser.authoring.Track, Container)}
     * to generate the child boxes.
     *
     * @param movie   concerned movie
     * @param isoFile concerned isofile
     * @return a complete 'mfra' box
     */
    protected Box createMfra(Movie movie, Container isoFile) {
        MovieFragmentRandomAccessBox mfra = new MovieFragmentRandomAccessBox();
        for (Track track : movie.getTracks()) {
            mfra.addBox(createTfra(track, isoFile));
        }

        MovieFragmentRandomAccessOffsetBox mfro = new MovieFragmentRandomAccessOffsetBox();
        mfra.addBox(mfro);
        mfro.setMfraSize(mfra.getSize());
        return mfra;
    }

    protected Box createTrex(Movie movie, Track track) {
        TrackExtendsBox trex = new TrackExtendsBox();
        trex.setTrackId(track.getTrackMetaData().getTrackId());
        trex.setDefaultSampleDescriptionIndex(1);
        trex.setDefaultSampleDuration(0);
        trex.setDefaultSampleSize(0);
        SampleFlags sf = new SampleFlags();
        if ("soun".equals(track.getHandler()) || "subt".equals(track.getHandler())) {
            // as far as I know there is no audio encoding
            // where the sample are not self contained.
            // same seems to be true for subtitle tracks
            sf.setSampleDependsOn(2);
            sf.setSampleIsDependedOn(2);
        }
        trex.setDefaultSampleFlags(sf);
        return trex;
    }

    /**
     * Creates a 'mvex' - movie extends box and populates it with 'trex' boxes
     * by calling {@link #createTrex(com.googlecode.mp4parser.authoring.Movie, com.googlecode.mp4parser.authoring.Track)}
     * for each track to generate them
     *
     * @param movie the source movie
     * @return a complete 'mvex'
     */
    protected Box createMvex(Movie movie) {
        MovieExtendsBox mvex = new MovieExtendsBox();
        final MovieExtendsHeaderBox mved = new MovieExtendsHeaderBox();
        mved.setVersion(1);
        for (Track track : movie.getTracks()) {
            final long trackDuration = getTrackDuration(movie, track);
            if (mved.getFragmentDuration() < trackDuration) {
                mved.setFragmentDuration(trackDuration);
            }
        }
        mvex.addBox(mved);

        for (Track track : movie.getTracks()) {
            mvex.addBox(createTrex(movie, track));
        }
        return mvex;
    }

    protected Box createTkhd(Movie movie, Track track) {
        TrackHeaderBox tkhd = new TrackHeaderBox();
        tkhd.setVersion(1);
        tkhd.setFlags(7); // enabled, in movie, in previe, in poster

        tkhd.setAlternateGroup(track.getTrackMetaData().getGroup());
        tkhd.setCreationTime(track.getTrackMetaData().getCreationTime());
        // We need to take edit list box into account in trackheader duration
        // but as long as I don't support edit list boxes it is sufficient to
        // just translate media duration to movie timescale
        tkhd.setDuration(0);//no duration in moov for fragmented movies
        tkhd.setHeight(track.getTrackMetaData().getHeight());
        tkhd.setWidth(track.getTrackMetaData().getWidth());
        tkhd.setLayer(track.getTrackMetaData().getLayer());
        tkhd.setModificationTime(getDate());
        tkhd.setTrackId(track.getTrackMetaData().getTrackId());
        tkhd.setVolume(track.getTrackMetaData().getVolume());
        return tkhd;
    }

    private long getTrackDuration(Movie movie, Track track) {
        return (track.getDuration() * movie.getTimescale()) / track.getTrackMetaData().getTimescale();
    }

    protected Box createMdhd(Movie movie, Track track) {
        MediaHeaderBox mdhd = new MediaHeaderBox();
        mdhd.setCreationTime(track.getTrackMetaData().getCreationTime());
        mdhd.setModificationTime(getDate());
        mdhd.setDuration(0);//no duration in moov for fragmented movies
        mdhd.setTimescale(track.getTrackMetaData().getTimescale());
        mdhd.setLanguage(track.getTrackMetaData().getLanguage());
        return mdhd;
    }

    protected Box createStbl(Movie movie, Track track) {
        SampleTableBox stbl = new SampleTableBox();

        createStsd(track, stbl);
        stbl.addBox(new TimeToSampleBox());
        stbl.addBox(new SampleToChunkBox());
        stbl.addBox(new SampleSizeBox());
        stbl.addBox(new StaticChunkOffsetBox());
        return stbl;
    }

    protected void createStsd(Track track, SampleTableBox stbl) {
        stbl.addBox(track.getSampleDescriptionBox());
    }

    protected Box createMinf(Track track, Movie movie) {
        MediaInformationBox minf = new MediaInformationBox();
        minf.addBox(track.getMediaHeaderBox());
        minf.addBox(createDinf(movie, track));
        minf.addBox(createStbl(movie, track));
        return minf;
    }

    protected Box createMdiaHdlr(Track track, Movie movie) {
        HandlerBox hdlr = new HandlerBox();
        hdlr.setHandlerType(track.getHandler());
        return hdlr;
    }

    protected Box createMdia(Track track, Movie movie) {
        MediaBox mdia = new MediaBox();
        mdia.addBox(createMdhd(movie, track));


        mdia.addBox(createMdiaHdlr(track, movie));


        mdia.addBox(createMinf(track, movie));
        return mdia;
    }

    protected Box createTrak(Track track, Movie movie) {
        LOG.fine("Creating Track " + track);
        TrackBox trackBox = new TrackBox();
        trackBox.addBox(createTkhd(movie, track));
        final Box edts = createEdts(track, movie);
        if (edts != null) {
            trackBox.addBox(edts);
        }
        trackBox.addBox(createMdia(track, movie));
        return trackBox;
    }

    protected Box createEdts(Track track, Movie movie) {
        final EditListBox elst = track.getTrackMetaData().getEditList();
        if (elst != null) {
            EditBox edts = new EditBox();
            edts.addBox(elst);
            return edts;
        } else {
            return null;
        }
    }

    protected DataInformationBox createDinf(Movie movie, Track track) {
        DataInformationBox dinf = new DataInformationBox();
        DataReferenceBox dref = new DataReferenceBox();
        dinf.addBox(dref);
        DataEntryUrlBox url = new DataEntryUrlBox();
        url.setFlags(1);
        dref.addBox(url);
        return dinf;
    }

    public FragmentIntersectionFinder getFragmentIntersectionFinder() {
        return intersectionFinder;
    }

    public void setIntersectionFinder(FragmentIntersectionFinder intersectionFinder) {
        this.intersectionFinder = intersectionFinder;
    }


}
