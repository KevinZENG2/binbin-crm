"""Recovery boundaries and the V27+ workspace-file regression; no live credentials."""
import contextlib
import importlib.util
import io
import pathlib
import sys
import types
import unittest
from unittest.mock import Mock, patch

SOURCE=pathlib.Path(__file__).resolve().parents[1]/'ops'/'restore_backup.py'
SPEC=importlib.util.spec_from_file_location('crm_manual_restore_under_test',SOURCE)
restore=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(restore)
FILE_ID='11111111-1111-4111-8111-111111111111'

def settings():
    return {'CRM_RESTORE_DATABASE_URL':'postgresql://test_user:fixture@postgres.railway.internal:5432/crm_restore_preview',
        'CRM_RESTORE_CONFIRM_DATABASE':'crm_restore_preview','CRM_RESTORE_OBJECT_PREFIX':'restore-preview',
        'CRM_RESTORE_BACKUP_KEY':'backups/preview/2026/10/26/002109-fixture.snapshot.aesgcm'}

def file_record(workspace=False):
    return {'id':('workspace-' if workspace else '')+FILE_ID,
        'objectKey':'preview/attachments/tenant/fixture.txt','bytes':4,'sha256':'a'*64}

def manifest(version=30,files=None):
    return {'schema':version,'databaseSha256':'b'*64,'attachments':files or []}

class ManualRestoreTests(unittest.TestCase):
    def test_current_backups_are_accepted(self):
        for version in (5,15,16,26,27,28,29,30):
            with self.subTest(version=version):self.assertEqual(restore.validate_manifest(manifest(version)),[])

    def test_unknown_or_noninteger_schema_is_rejected(self):
        for version in (4,31,True,'28',None):
            with self.subTest(version=version),self.assertRaisesRegex(RuntimeError,'KNOWN_BACKUP_SCHEMA_REQUIRED'):
                restore.validate_manifest(manifest(version))

    def test_workspace_file_keeps_zip_prefix_and_maps_to_correct_table(self):
        actual=restore.validate_manifest(manifest(28,[file_record(True)]))
        self.assertEqual(actual,[('crm_workspace_attachment',FILE_ID,'workspace-'+FILE_ID)])

    def test_customer_file_maps_to_customer_attachment_table(self):
        self.assertEqual(restore.attachment_identity(file_record(),30),('crm_attachment',FILE_ID,FILE_ID))

    def test_workspace_file_in_old_schema_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError,'RESTORE_WORKSPACE_SCHEMA_REQUIRED'):
            restore.validate_manifest(manifest(26,[file_record(True)]))

    def test_duplicate_file_identity_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError,'RESTORE_DUPLICATE_FILE_ID'):
            restore.validate_manifest(manifest(30,[file_record(True),file_record(True)]))

    def test_file_names_cannot_traverse_the_archive(self):
        item=file_record();item['id']='../'+FILE_ID
        with self.assertRaisesRegex(RuntimeError,'RESTORE_FILE_ID_INVALID'):restore.attachment_identity(item,30)

    def test_source_object_must_stay_in_original_attachment_namespace(self):
        for key in ('backups/preview/snapshot','preview/attachments/../other','other/attachments/file'):
            item=file_record();item['objectKey']=key
            with self.subTest(key=key),self.assertRaisesRegex(RuntimeError,'RESTORE_SOURCE_NAMESPACE_INVALID'):
                restore.attachment_identity(item,30)

    def test_malformed_file_metadata_is_rejected(self):
        for field,value in (('sha256','invalid'),('bytes',-1),('bytes',True)):
            item=file_record();item[field]=value
            with self.subTest(field=field,value=value),self.assertRaisesRegex(RuntimeError,'RESTORE_FILE_METADATA_INVALID'):
                restore.attachment_identity(item,30)

    def test_live_database_name_is_never_an_allowed_target(self):
        env=settings();env['CRM_RESTORE_DATABASE_URL']='postgresql://test_user:fixture@postgres.railway.internal/railway'
        env['CRM_RESTORE_CONFIRM_DATABASE']='railway'
        with self.assertRaisesRegex(RuntimeError,'EXPLICIT_NEW_RESTORE_DATABASE_REQUIRED'):restore.restore_target(env)

    def test_public_database_host_is_rejected(self):
        env=settings();env['CRM_RESTORE_DATABASE_URL']=env['CRM_RESTORE_DATABASE_URL'].replace('postgres.railway.internal','db.example.com')
        with self.assertRaisesRegex(RuntimeError,'EXPLICIT_NEW_RESTORE_DATABASE_REQUIRED'):restore.restore_target(env)

    def test_confirmation_and_new_file_namespace_are_required(self):
        env=settings();env['CRM_RESTORE_CONFIRM_DATABASE']='crm_restore_other'
        with self.assertRaisesRegex(RuntimeError,'EXPLICIT_NEW_RESTORE_DATABASE_REQUIRED'):restore.restore_target(env)
        env=settings();env['CRM_RESTORE_OBJECT_PREFIX']='preview'
        with self.assertRaisesRegex(RuntimeError,'NEW_RESTORE_OBJECT_NAMESPACE_REQUIRED'):restore.restore_target(env)

    def test_current_private_new_database_is_allowed(self):
        _,name,prefix,key=restore.restore_target(settings())
        self.assertEqual((name,prefix),('crm_restore_preview','restore-preview'))
        self.assertTrue(key.endswith('.snapshot.aesgcm'))

    def test_nonempty_target_stops_before_storage_or_restore_commands(self):
        cursor=Mock();cursor.__enter__=Mock(return_value=cursor);cursor.__exit__=Mock(return_value=False)
        cursor.fetchone.return_value=(1,)
        database=Mock();database.cursor.return_value=cursor
        boto=types.ModuleType('boto3');boto.client=Mock()
        pg=types.ModuleType('psycopg2');pg.connect=Mock(return_value=database)
        config=types.ModuleType('botocore.config');config.Config=Mock()
        backup=types.ModuleType('backup')
        for name in ('decrypt','digest','command','table_counts','validate_schema'):setattr(backup,name,Mock())
        modules={'boto3':boto,'psycopg2':pg,'botocore':types.ModuleType('botocore'),'botocore.config':config,'backup':backup}
        with patch.dict(sys.modules,modules),patch.dict(restore.os.environ,settings(),clear=True),contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError,'RESTORE_FAILED_NEW_TARGET_ONLY'):restore.main()
        boto.client.assert_not_called();backup.command.assert_not_called();database.close.assert_called_once()

if __name__=='__main__':unittest.main()
