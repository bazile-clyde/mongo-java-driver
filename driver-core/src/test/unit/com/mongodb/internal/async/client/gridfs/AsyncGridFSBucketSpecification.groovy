/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.internal.async.client.gridfs

import com.mongodb.MongoException
import com.mongodb.MongoGridFSException
import com.mongodb.MongoNamespace
import com.mongodb.ReadConcern
import com.mongodb.WriteConcern
import com.mongodb.async.FutureResultCallback
import com.mongodb.client.gridfs.model.GridFSDownloadOptions
import com.mongodb.client.gridfs.model.GridFSFile
import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.UpdateResult
import com.mongodb.internal.async.AsyncBatchCursor
import com.mongodb.internal.async.SingleResultCallback
import com.mongodb.internal.async.client.AsyncClientSession
import com.mongodb.internal.async.client.AsyncFindIterable
import com.mongodb.internal.async.client.AsyncMongoClients
import com.mongodb.internal.async.client.AsyncMongoCollection
import com.mongodb.internal.async.client.AsyncMongoDatabaseImpl
import com.mongodb.internal.async.client.OperationExecutor
import com.mongodb.internal.async.client.TestOperationExecutor
import com.mongodb.internal.operation.FindOperation
import org.bson.BsonDocument
import org.bson.BsonObjectId
import org.bson.BsonString
import org.bson.Document
import org.bson.UuidRepresentation
import org.bson.codecs.DocumentCodecProvider
import org.bson.types.ObjectId
import spock.lang.Specification
import spock.lang.Unroll

import java.nio.ByteBuffer

import static com.mongodb.CustomMatchers.isTheSameAs
import static com.mongodb.ReadPreference.primary
import static com.mongodb.ReadPreference.secondary
import static org.bson.codecs.configuration.CodecRegistries.fromProviders
import static spock.util.matcher.HamcrestSupport.expect

@SuppressWarnings(['ClosureAsLastMethodParameter', 'ClassSize'])
class AsyncGridFSBucketSpecification extends Specification {

    def readConcern = ReadConcern.DEFAULT
    def uuidRepresentation = UuidRepresentation.STANDARD
    def registry = AsyncMongoClients.defaultCodecRegistry
    def database = databaseWithExecutor(Stub(OperationExecutor))
    def databaseWithExecutor(OperationExecutor executor) {
        new AsyncMongoDatabaseImpl('test', registry, primary(), WriteConcern.ACKNOWLEDGED, true, true, readConcern, uuidRepresentation,
                executor)
    }

    def 'should return the correct bucket name'() {
        when:
        def bucketName = new AsyncGridFSBucketImpl(database).getBucketName()

        then:
        bucketName == 'fs'

        when:
        bucketName = new AsyncGridFSBucketImpl(database, 'custom').getBucketName()

        then:
        bucketName == 'custom'
    }

    def 'should behave correctly when using withChunkSizeBytes'() {
        given:
        def newChunkSize = 200

        when:
        def gridFSBucket = new AsyncGridFSBucketImpl(database).withChunkSizeBytes(newChunkSize)

        then:
        gridFSBucket.getChunkSizeBytes() == newChunkSize
    }

    def 'should behave correctly when using withReadPreference'() {
        given:
        def filesCollection = Mock(AsyncMongoCollection)
        def chunksCollection = Mock(AsyncMongoCollection)
        def newReadPreference = secondary()

        when:
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, chunksCollection)
                .withReadPreference(newReadPreference)

        then:
        1 * filesCollection.withReadPreference(newReadPreference) >> filesCollection
        1 * chunksCollection.withReadPreference(newReadPreference) >> chunksCollection

        when:
        gridFSBucket.getReadConcern()

        then:
        1 * filesCollection.getReadConcern()
    }

    def 'should behave correctly when using withWriteConcern'() {
        given:
        def filesCollection = Mock(AsyncMongoCollection)
        def chunksCollection = Mock(AsyncMongoCollection)
        def newWriteConcern = WriteConcern.MAJORITY

        when:
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, chunksCollection)
                .withWriteConcern(newWriteConcern)

        then:
        1 * filesCollection.withWriteConcern(newWriteConcern) >> filesCollection
        1 * chunksCollection.withWriteConcern(newWriteConcern) >> chunksCollection

        when:
        gridFSBucket.getWriteConcern()

        then:
        1 * filesCollection.getWriteConcern()
    }

    def 'should behave correctly when using withReadConcern'() {
        given:
        def filesCollection = Mock(AsyncMongoCollection)
        def chunksCollection = Mock(AsyncMongoCollection)
        def newReadConcern = ReadConcern.MAJORITY

        when:
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, chunksCollection)
                .withReadConcern(newReadConcern)

        then:
        1 * filesCollection.withReadConcern(newReadConcern) >> filesCollection
        1 * chunksCollection.withReadConcern(newReadConcern) >> chunksCollection

        when:
        gridFSBucket.getReadConcern()

        then:
        1 * filesCollection.getReadConcern() >> newReadConcern
    }

    def 'should get defaults from MongoDatabase'() {
        given:
        def defaultChunkSizeBytes = 255 * 1024
        def database = new AsyncMongoDatabaseImpl('test', fromProviders(new DocumentCodecProvider()), secondary(),
                WriteConcern.ACKNOWLEDGED, true, true, readConcern, uuidRepresentation, new TestOperationExecutor([]))

        when:
        def gridFSBucket = new AsyncGridFSBucketImpl(database)

        then:
        gridFSBucket.getChunkSizeBytes() == defaultChunkSizeBytes
        gridFSBucket.getReadPreference() == database.getReadPreference()
        gridFSBucket.getWriteConcern() == database.getWriteConcern()
        gridFSBucket.getReadConcern() == database.getReadConcern()
    }

    def 'should create the expected GridFSUploadStream'() {
        given:
        def filesCollection = Stub(AsyncMongoCollection)
        def chunksCollection = Stub(AsyncMongoCollection)
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, chunksCollection)

        when:
        def stream
        if (clientSession != null){
            stream = gridFSBucket.openUploadStream(clientSession, 'filename')
        } else {
            stream = gridFSBucket.openUploadStream('filename')
        }

        then:
        expect stream, isTheSameAs(new AsyncGridFSUploadStreamImpl(clientSession, filesCollection, chunksCollection, stream.getId(),
                'filename', 255, null, new GridFSIndexCheckImpl(clientSession, filesCollection, chunksCollection)),
                ['closeAndWritingLock'])

        where:
        clientSession << [null, Stub(AsyncClientSession)]
    }

    def 'should create the expected GridFSDownloadStream'() {
        given:
        def fileId = new BsonObjectId(new ObjectId())
        def findIterable = Mock(AsyncFindIterable)
        def gridFSFindIterable = new AsyncGridFSFindIterableImpl(findIterable)
        def filesCollection = Mock(AsyncMongoCollection)
        def chunksCollection = Stub(AsyncMongoCollection)
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, chunksCollection)

        when:
        def stream
        if (clientSession != null){
            stream = gridFSBucket.openDownloadStream(clientSession, fileId.getValue())
        } else {
            stream = gridFSBucket.openDownloadStream(fileId.getValue())
        }

        then:
        if (clientSession != null){
            1 * filesCollection.find(clientSession) >> findIterable
        } else {
            1 * filesCollection.find() >> findIterable
        }
        1 * findIterable.filter(_) >> findIterable

        then:
        expect stream, isTheSameAs(new AsyncGridFSDownloadStreamImpl(clientSession, gridFSFindIterable, chunksCollection),
                ['closeAndReadingLock', 'resultsQueue'])

        where:
        clientSession << [null, Stub(AsyncClientSession)]
    }

    def 'should throw an exception if file not found'() {
        given:
        def fileId = new ObjectId()
        def findIterable = Mock(AsyncFindIterable)
        def filesCollection = Mock(AsyncMongoCollection)
        def chunksCollection = Stub(AsyncMongoCollection)
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, chunksCollection)

        when:
        def futureResult = new FutureResultCallback()

        def stream
        if (clientSession != null){
            stream = gridFSBucket.openDownloadStream(clientSession, fileId)
        } else {
            stream = gridFSBucket.openDownloadStream(fileId)
        }
        stream.read(ByteBuffer.wrap(new byte[10]), futureResult)
        futureResult.get()

        then:

        if (clientSession != null){
            1 * filesCollection.find(clientSession) >> findIterable
        } else {
            1 * filesCollection.find() >> findIterable
        }
        1 * findIterable.filter(new Document('_id', fileId)) >> findIterable
        1 * findIterable.first(_) >> { it.last().onResult(null, null) }
        thrown(MongoGridFSException)

        where:
        clientSession << [null, Stub(AsyncClientSession)]
    }

    @Unroll
    def 'should create the expected GridFSDownloadStream when opening by name with version: #version'() {
        given:
        def filename = 'filename'
        def fileId = new ObjectId()
        def bsonFileId = new BsonObjectId(fileId)
        def fileInfo = new GridFSFile(bsonFileId, filename, 10, 255, new Date(), new Document())
        def findIterable = Mock(AsyncFindIterable)
        def filesCollection = Mock(AsyncMongoCollection)
        def chunksCollection = Stub(AsyncMongoCollection)
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, chunksCollection)

        when:
        def futureResult = new FutureResultCallback()
        def stream
        if (clientSession != null) {
            stream = gridFSBucket.openDownloadStream(clientSession, filename, new GridFSDownloadOptions().revision(version))
        } else {
            stream = gridFSBucket.openDownloadStream(filename, new GridFSDownloadOptions().revision(version))
        }
        stream.getGridFSFile(futureResult)
        futureResult.get()

        then:
        if (clientSession != null) {
            1 * filesCollection.find(clientSession) >> findIterable
        } else {
            1 * filesCollection.find() >> findIterable
        }
        1 * findIterable.filter(new Document('filename', filename)) >> findIterable
        1 * findIterable.skip(skip) >> findIterable
        1 * findIterable.sort(new Document('uploadDate', sortOrder)) >> findIterable
        1 * findIterable.first(_) >> { it.last().onResult(fileInfo, null) }

        where:
        version | skip | sortOrder  | clientSession
        0       | 0    | 1          | null
        1       | 1    | 1          | null
        2       | 2    | 1          | null
        3       | 3    | 1          | null
        -3      | 2    | -1         | null
        -1      | 0    | -1         | null
        -2      | 1    | -1         | null
        0       | 0    | 1          | Stub(AsyncClientSession)
        1       | 1    | 1          | Stub(AsyncClientSession)
        2       | 2    | 1          | Stub(AsyncClientSession)
        3       | 3    | 1          | Stub(AsyncClientSession)
        -3      | 2    | -1         | Stub(AsyncClientSession)
        -1      | 0    | -1         | Stub(AsyncClientSession)
        -2      | 1    | -1         | Stub(AsyncClientSession)

        // todo
    }

    def 'should create the expected GridFSFindIterable'() {
        given:
        def collection = Mock(AsyncMongoCollection)
        def findIterable = Mock(AsyncFindIterable)
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, collection, Stub(AsyncMongoCollection))


        when:
        def result = gridFSBucket.find()

        then:
        1 * collection.find() >> findIterable
        expect result, isTheSameAs(new AsyncGridFSFindIterableImpl(findIterable))

        where:
        clientSession << [null, Stub(AsyncClientSession)]
    }

    def 'should execute the expected FindOperation when finding a file'() {
        given:
        def executor = new TestOperationExecutor([Stub(AsyncBatchCursor), Stub(AsyncBatchCursor)])
        def database = databaseWithExecutor(executor)
        def gridFSBucket = new AsyncGridFSBucketImpl(database)
        def decoder = registry.get(GridFSFile)
        def callback = Stub(SingleResultCallback)

        when:
        gridFSBucket.find().batchCursor(callback)

        then:
        executor.getReadPreference() == primary()
        expect executor.getReadOperation(), isTheSameAs(new FindOperation<GridFSFile>(new MongoNamespace('test.fs.files'), decoder)
                .filter(new BsonDocument()).retryReads(true))

        when:
        def filter = new BsonDocument('filename', new BsonString('filename'))
        def readConcern = ReadConcern.MAJORITY
        gridFSBucket.withReadPreference(secondary()).withReadConcern(readConcern).find(filter).batchCursor(callback)

        then:
        executor.getReadPreference() == secondary()
        expect executor.getReadOperation(), isTheSameAs(new FindOperation<GridFSFile>(new MongoNamespace('test.fs.files'), decoder)
                .filter(filter).slaveOk(true).retryReads(true))

        where:
        clientSession << [null, Stub(AsyncClientSession)]
    }

    def 'should throw an exception if file not found when opening by name'() {
        given:
        def findIterable = Mock(AsyncFindIterable)
        def filesCollection = Mock(AsyncMongoCollection)
        def chunksCollection = Stub(AsyncMongoCollection)
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, chunksCollection)
        when:
        def futureResult = new FutureResultCallback()
        def stream
        if (clientSession != null) {
            stream = gridFSBucket.openDownloadStream(clientSession, 'filename')
        } else {
            stream = gridFSBucket.openDownloadStream('filename')
        }
        stream.read(ByteBuffer.wrap(new byte[10]), futureResult)
        futureResult.get()

        then:
        if (clientSession != null) {
            1 * filesCollection.find(clientSession) >> findIterable
        } else {
            1 * filesCollection.find() >> findIterable
        }
        1 * findIterable.filter(new Document('filename', 'filename')) >> findIterable
        1 * findIterable.skip(0) >> findIterable
        1 * findIterable.sort(new Document('uploadDate', -1)) >> findIterable
        1 * findIterable.first(_) >> { it.last().onResult(null, null) }

        then:
        thrown(MongoGridFSException)

        where:
        clientSession << [null, Stub(AsyncClientSession)]
    }

    def 'should delete from files collection then chunks collection'() {
        given:
        def fileId = new ObjectId()
        def filesCollection = Mock(AsyncMongoCollection)
        def chunksCollection = Mock(AsyncMongoCollection)
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, chunksCollection)

        when:
        def futureResult = new FutureResultCallback()
        gridFSBucket.delete(fileId, futureResult)
        futureResult.get()

        then:
        1 * filesCollection.deleteOne(new Document('_id', new BsonObjectId(fileId)), _) >> {
            it.last().onResult(DeleteResult.acknowledged(1), null)
        }
        1 * chunksCollection.deleteMany(new Document('files_id', new BsonObjectId(fileId)), _) >> {
            it.last().onResult(DeleteResult.acknowledged(1), null)
        }

        where:
        clientSession << [null, Stub(AsyncClientSession)]
    }

    def 'should throw an exception when deleting if no record in the files collection'() {
        given:
        def fileId = new ObjectId()
        def filesCollection = Mock(AsyncMongoCollection)
        def chunksCollection = Mock(AsyncMongoCollection)
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, chunksCollection)

        when:
        def futureResult = new FutureResultCallback()
        gridFSBucket.delete(fileId, futureResult)
        futureResult.get()

        then:
        1 * filesCollection.deleteOne(new Document('_id', new BsonObjectId(fileId)), _) >> {
            it.last().onResult(DeleteResult.acknowledged(0), null)
        }
        1 * chunksCollection.deleteMany(new Document('files_id', new BsonObjectId(fileId)), _) >> {
            it.last().onResult(DeleteResult.acknowledged(1), null)
        }

        then:
        thrown(MongoGridFSException)

        where:
        clientSession << [null, Stub(AsyncClientSession)]
    }

    def 'should propagate exceptions when deleting'() {
        given:
        def fileId = new ObjectId()
        def filesCollection = Mock(AsyncMongoCollection)
        def chunksCollection = Mock(AsyncMongoCollection)
        def deleteException = new MongoException('delete failed')
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, chunksCollection)

        when:
        def futureResult = new FutureResultCallback()
        gridFSBucket.delete(fileId, futureResult)

        then:
        1 * filesCollection.deleteOne(new Document('_id', new BsonObjectId(fileId)), _) >> {
            it.last().onResult(null, deleteException)
        }

        when:
        futureResult.get()

        then:
        def exception = thrown(MongoException)
        exception == deleteException

        when:
        futureResult = new FutureResultCallback()
        gridFSBucket.delete(fileId, futureResult)

        then:
        1 * filesCollection.deleteOne(new Document('_id', new BsonObjectId(fileId)), _) >> {
            it.last().onResult(DeleteResult.acknowledged(0), null)
        }
        1 * chunksCollection.deleteMany(new Document('files_id', new BsonObjectId(fileId)), _) >> {
            it.last().onResult(null, deleteException)
        }

        when:
        futureResult.get()

        then:
        exception = thrown(MongoException)
        exception == deleteException

        where:
        clientSession << [null, Stub(AsyncClientSession)]
    }

    def 'should rename a file'() {
        given:
        def fileId = new ObjectId()
        def filesCollection = Mock(AsyncMongoCollection)
        def newFilename = 'newFilename'
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, Stub(AsyncMongoCollection))

        when:
        def futureResult = new FutureResultCallback()
        gridFSBucket.rename(fileId, newFilename, futureResult)
        futureResult.get()

        then:
        1 * filesCollection.updateOne(new BsonDocument('_id', new BsonObjectId(fileId)),
                new BsonDocument('$set',
                        new BsonDocument('filename', new BsonString(newFilename))), _) >> {
            it.last().onResult(new UpdateResult.UnacknowledgedUpdateResult(), null)
        }

        where:
        clientSession << [null, Stub(AsyncClientSession)]
    }

    def 'should throw an exception renaming non existent file'() {
        given:
        def fileId = new ObjectId()
        def filesCollection = Mock(AsyncMongoCollection)
        def newFilename = 'newFilename'
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, Stub(AsyncMongoCollection))

        when:
        def futureResult = new FutureResultCallback()
        gridFSBucket.rename(fileId, newFilename, futureResult)
        futureResult.get()

        then:
        1 * filesCollection.updateOne(_, _, _) >> { it.last().onResult(new UpdateResult.AcknowledgedUpdateResult(0, 0, null), null) }

        then:
        thrown(MongoGridFSException)

        where:
        clientSession << [null, Stub(AsyncClientSession)]
    }

    def 'should handle exceptions when renaming a file'() {
        given:
        def fileId = new ObjectId()
        def filesCollection = Mock(AsyncMongoCollection)
        def newFilename = 'newFilename'
        def exception =  new MongoException('failed')
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, Stub(AsyncMongoCollection))

        when:
        def futureResult = new FutureResultCallback()
        gridFSBucket.rename(fileId, newFilename, futureResult)
        futureResult.get()

        then:
        1 * filesCollection.updateOne(_, _, _) >> { it.last().onResult(null, exception) }

        then:
        def e = thrown(MongoException)
        e == exception

        where:
        clientSession << [null, Stub(AsyncClientSession)]
    }

    def 'should be able to drop the bucket'() {
        given:
        def filesCollection = Mock(AsyncMongoCollection)
        def chunksCollection = Mock(AsyncMongoCollection)
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, chunksCollection)

        when:
        def futureResult = new FutureResultCallback()
        gridFSBucket.drop(futureResult)
        futureResult.get()

        then:
        1 * filesCollection.drop(_) >> { it.last().onResult(null, null) }
        1 * chunksCollection.drop(_) >> { it.last().onResult(null, null) }

        where:
        clientSession << [null, Stub(AsyncClientSession)]
    }

    def 'should handle exceptions when dropping the bucket'() {
        given:
        def filesCollection = Mock(AsyncMongoCollection)
        def chunksCollection = Mock(AsyncMongoCollection)
        def exception =  new MongoException('failed')
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, chunksCollection)

        when:
        def futureResult = new FutureResultCallback()
        gridFSBucket.drop(futureResult)
        futureResult.get()

        then:
        1 * filesCollection.drop(_) >> { it.last().onResult(null, exception) }

        then:
        def e = thrown(MongoException)
        e == exception

        when:
        futureResult = new FutureResultCallback()
        gridFSBucket.drop(futureResult)
        futureResult.get()

        then:
        1 * filesCollection.drop(_) >> { it.last().onResult(null, null) }
        1 * chunksCollection.drop(_) >> { it.last().onResult(null, exception) }

        then:
        e = thrown(MongoException)
        e == exception
    }

    def 'should validate the clientSession is not null'() {
        given:
        def objectId = new ObjectId()
        def bsonValue = new BsonObjectId(objectId)
        def filename = 'filename'
        def filesCollection = Mock(AsyncMongoCollection)
        def chunksCollection = Mock(AsyncMongoCollection)
        def callback = Stub(SingleResultCallback)
        def gridFSBucket = new AsyncGridFSBucketImpl('fs', 255, filesCollection, chunksCollection)

        when:
        gridFSBucket.delete(null, objectId, callback)
        then:
        thrown(IllegalArgumentException)

        when:
        gridFSBucket.drop(null, callback)
        then:
        thrown(IllegalArgumentException)

        when:
        gridFSBucket.find((AsyncClientSession) null)
        then:
        thrown(IllegalArgumentException)

        when:
        gridFSBucket.find((AsyncClientSession) null, new Document())
        then:
        thrown(IllegalArgumentException)

        when:
        gridFSBucket.openDownloadStream(null, filename)
        then:
        thrown(IllegalArgumentException)

        when:
        gridFSBucket.openDownloadStream(null, objectId)
        then:
        thrown(IllegalArgumentException)

        when:
        gridFSBucket.openUploadStream(null, filename)
        then:
        thrown(IllegalArgumentException)

        when:
        gridFSBucket.openUploadStream(null, bsonValue, filename)
        then:
        thrown(IllegalArgumentException)

        when:
        gridFSBucket.rename(null, objectId, filename, callback)
        then:
        thrown(IllegalArgumentException)
    }
}
