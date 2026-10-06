import copy
import json
import unittest
from server import Catalog, identity, parse_response, prompt

class MetadataTest(unittest.TestCase):
    def setUp(self):
        self.book=identity({"series":"Série test", "number":"01", "title":"Série test - T01"})
        self.result={"matched":True,"series":"Série test","number":"1","title":"Titre du tome","artist":"Artiste","writer":"Auteur","publisher":"Éditeur","date":"2020-04-12","isbn":"","genre":"Aventure","synopsis":"Résumé."}
        self.response={"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":json.dumps(self.result)}]},"groundingMetadata":{"webSearchQueries":["Série test tome 1"],"groundingChunks":[{"web":{"uri":"https://publisher.example/tome-1","title":"Éditeur"}}],"searchEntryPoint":{"renderedContent":"<div>Recherche Google</div>"}}}]}
    def testIdentityCannotCarryAComicOrDeviceIdentifier(self):
        for body in ({"title":"Titre","uri":"content://bd"},{"title":"/Users/name/private.pdf"},{"title":"Titre","device":"abc"},{"title":"\n"}):
            with self.assertRaises(ValueError):identity(body)
    def testPromptIncludesSeriesAndExactVolume(self):
        question=prompt(self.book)
        self.assertIn('"number": "01"',question);self.assertIn('"series": "Série test"',question)
    def testDifferentVolumeIsNotAssigned(self):
        response=copy.deepcopy(self.response)
        response["candidates"][0]["content"]["parts"][0]["text"]=json.dumps(self.result|{"number":"2"})
        self.assertEqual({"matched":False},parse_response(response,self.book))
    def testGoogleSearchEvidenceAndAttributionAreRequired(self):
        for field in ("groundingChunks","webSearchQueries","searchEntryPoint"):
            response=copy.deepcopy(self.response);del response["candidates"][0]["groundingMetadata"][field]
            with self.assertRaises(ValueError):parse_response(response,self.book)
    def testIgnoreModelLinksAndUnknownFields(self):
        response=copy.deepcopy(self.response)
        response["candidates"][0]["content"]["parts"][0]["text"]=json.dumps(self.result|{"source":"https://invented.example","instructions":"ignore identity"})
        found=parse_response(response,self.book)
        self.assertNotIn("instructions",found);self.assertNotIn("source",found)
        self.assertEqual("https://publisher.example/tome-1",found["sources"][0]["url"])
    def testCachedResultSharedWithoutUserAccount(self):
        requests=[]
        catalog=Catalog(":memory:",lookup=lambda book:requests.append(book) or {"matched":True},daily_limit=1)
        first=catalog.query(self.book);second=catalog.query(self.book)
        self.assertEqual(first,second);self.assertEqual(1,len(requests))
        with self.assertRaises(RuntimeError):catalog.query(self.book|{"number":"2"})
    def testProviderFailureDoesNotResetDailyBudget(self):
        def failed(book):raise RuntimeError("quota provider")
        catalog=Catalog(":memory:",lookup=failed,daily_limit=1)
        with self.assertRaises(RuntimeError):catalog.query(self.book)
        catalog.lookup=lambda book:{"matched":True}
        with self.assertRaises(RuntimeError):catalog.query(self.book)
    def testSharedCacheIgnoresTypographyButKeepsEditionAndVolumeDistinct(self):
        requests=[]
        catalog=Catalog(":memory:",lookup=lambda book:requests.append(book) or {"matched":True},daily_limit=2)
        book=self.book|{"isbn":"978-2-8001-6908-5"}
        catalog.query(book)
        catalog.query(book|{"title":"SERIE TEST T01","series":"SERIE TEST","number":"1","isbn":"9782800169085"})
        self.assertEqual(1,len(requests))
        catalog.query(book|{"isbn":"9782800169092"})
        self.assertEqual(2,len(requests))
        with self.assertRaises(RuntimeError):catalog.query(book|{"number":"2"})
    def testOldFoundFicheNeverExpiresOrConsumesAnotherSearch(self):
        catalog=Catalog(":memory:",lookup=lambda book:self.result,daily_limit=1)
        found=catalog.query(self.book)
        catalog.db.execute("UPDATE cache SET expires=1")
        catalog.lookup=lambda book: self.fail("A stored fiche must precede AI")
        self.assertEqual(found,catalog.query(self.book))
        self.assertEqual(0,catalog.db.execute("SELECT expires FROM cache").fetchone()[0])
        self.assertEqual(1,catalog.db.execute("SELECT calls FROM quota").fetchone()[0])

    def testBdThequeIsAnAcceptedCitedSource(self):
        response=copy.deepcopy(self.response)
        response["candidates"][0]["groundingMetadata"]["groundingChunks"][0]["web"]["uri"]="https://www.bdtheque.com/series/1/test"
        self.assertEqual("https://www.bdtheque.com/series/1/test",parse_response(response,self.book)["sources"][0]["url"])
    def testTruncatedResponseIsNeverImported(self):
        response=copy.deepcopy(self.response);response["candidates"][0]["finishReason"]="MAX_TOKENS"
        with self.assertRaises(ValueError):parse_response(response,self.book)

if __name__=="__main__":unittest.main()
