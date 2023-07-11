

INSERT INTO `iam_user_info` (`ID`,`BIRTHDATE`,`EMAIL`,`EMAILVERIFIED`,`FAMILYNAME`,`GENDER`,`GIVENNAME`,`LOCALE`,`MIDDLENAME`,`NICKNAME`,`PHONENUMBER`,`PHONENUMBERVERIFIED`,`PICTURE`,`PROFILE`,`WEBSITE`,`ZONEINFO`,`ADDRESS_ID`,`DTYPE`) VALUES (2,NULL,'test.user.1@iam.com',1,'User 1',NULL,'Test',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL);
INSERT INTO `iam_account` (`ID`,`active`,`CREATIONTIME`,`LASTUPDATETIME`,`PASSWORD`,`USERNAME`,`UUID`,`user_info_id`,`confirmation_key`,`reset_key`,`provisioned`,`last_login_time`,`end_time`) VALUES (2,1,'2023-07-02 04:37:06','2023-07-02 04:40:00','$2a$10$wZWyBtXf0gEfdqEJLB1NGOPdYmlJoiOXnkf/OL2X8XUqBrMgtdjyW','test-user-1','1baa8eda-2a18-e6d3-af10-fc4bbd7e6556',2,NULL,NULL,0,'2023-07-02 04:37:52',NULL);
INSERT INTO `iam_account_authority` (`account_id`,`authority_id`) VALUES (2,2);

INSERT INTO `iam_user_info` (`ID`,`BIRTHDATE`,`EMAIL`,`EMAILVERIFIED`,`FAMILYNAME`,`GENDER`,`GIVENNAME`,`LOCALE`,`MIDDLENAME`,`NICKNAME`,`PHONENUMBER`,`PHONENUMBERVERIFIED`,`PICTURE`,`PROFILE`,`WEBSITE`,`ZONEINFO`,`ADDRESS_ID`,`DTYPE`) VALUES (3,NULL,'test.user.2@iam.com',1,'User 2',NULL,'Test',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL);
INSERT INTO `iam_account` (`ID`,`active`,`CREATIONTIME`,`LASTUPDATETIME`,`PASSWORD`,`USERNAME`,`UUID`,`user_info_id`,`confirmation_key`,`reset_key`,`provisioned`,`last_login_time`,`end_time`) VALUES (3,1,'2023-07-02 04:51:16','2023-07-02 04:51:16','$2a$10$wZWyBtXf0gEfdqEJLB1NGOPdYmlJoiOXnkf/OL2X8XUqBrMgtdjyW','test-user-2','3f482c63-9c9f-143b-7672-f43322d5b81c',3,NULL,NULL,0,NULL,NULL);
INSERT INTO `iam_account_authority` (`account_id`,`authority_id`) VALUES (3,2);

INSERT INTO `iam_group` (`ID`,`CREATIONTIME`,`DESCRIPTION`,`LASTUPDATETIME`,`name`,`UUID`,`parent_group_id`,`default_group`) VALUES (1,'2023-07-02 12:01:34',NULL,'2023-07-02 12:01:34','mini-src-test-group','67e30b44-e17d-4dd0-87dc-0f4ea2befdbc',NULL,0);

INSERT INTO `iam_account_group` (`account_id`,`group_id`,`creation_time`,`end_time`) VALUES (2,1,'2023-07-02 04:40:00',NULL);

INSERT INTO `client_details` (`id`,`client_description`,`reuse_refresh_tokens`,`dynamically_registered`,`allow_introspection`,`id_token_validity_seconds`,`client_id`,`client_secret`,`access_token_validity_seconds`,`refresh_token_validity_seconds`,`application_type`,`client_name`,`token_endpoint_auth_method`,`subject_type`,`logo_uri`,`policy_uri`,`client_uri`,`tos_uri`,`jwks_uri`,`jwks`,`sector_identifier_uri`,`request_object_signing_alg`,`user_info_signed_response_alg`,`user_info_encrypted_response_alg`,`user_info_encrypted_response_enc`,`id_token_signed_response_alg`,`id_token_encrypted_response_alg`,`id_token_encrypted_response_enc`,`token_endpoint_auth_signing_alg`,`default_max_age`,`require_auth_time`,`created_at`,`initiate_login_uri`,`clear_access_tokens_on_refresh`,`software_statement`,`code_challenge_method`,`software_id`,`software_version`,`device_code_validity_seconds`) VALUES (2,'Test Client for IAM integration test',0,0,1,600,'c942d267-b8d9-4d07-9696-85f9020c49d1','AOifEfPYxZyp68hMhU7COgk0Rfk73dVJel4jFLQKXNhMAQimg8s-TQBLJMzTDgZNAFJ-WSX1DrYI0uK4X2ybvIg',3600,2592000,NULL,'test-client','SECRET_BASIC',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,'2023-07-02 04:25:11',NULL,0,NULL,NULL,NULL,NULL,600);

INSERT INTO `client_grant_type` (`owner_id`,`grant_type`) VALUES (2,'password');

INSERT INTO `client_scope` (`owner_id`,`scope`) VALUES (2,'openid');
INSERT INTO `client_scope` (`owner_id`,`scope`) VALUES (2,'profile');
INSERT INTO `client_scope` (`owner_id`,`scope`) VALUES (2,'email');